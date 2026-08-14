package io.github.shri299.wirefin.runtime;

import io.github.shri299.wirefin.icmp.*;
import io.github.shri299.wirefin.ipv4.*;
import io.github.shri299.wirefin.ipv6.*;
import io.github.shri299.wirefin.socket.UdpReceivedDatagram;
import io.github.shri299.wirefin.tcp.*;
import io.github.shri299.wirefin.tcp.connection.TcpConnection;
import io.github.shri299.wirefin.udp.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class DualStackPacketProcessorTest {
    private final Ipv4Address local4 = Ipv4Address.parse("10.0.0.2"), remote4 = Ipv4Address.parse("10.0.0.1");
    private final Ipv6Address local6 = Ipv6Address.parse("fd00:77::2"), remote6 = Ipv6Address.parse("fd00:77::1");
    private PacketProcessor processor() {
        return new PacketProcessor(List.of(local4, local6), () -> 100, ignored -> {}, () -> 1,
                TcpConnection.Config.defaults());
    }
    @Test void answersIpv4AndIpv6Echo() {
        var p = processor();
        byte[] req4 = IcmpCodec.serializeV4(new IcmpMessage(8, 0, 0x10002, new byte[]{9}));
        byte[] wire4 = Ipv4Codec.serialize(new Ipv4Packet(0, 1, 2, 0, 64, 1, remote4, local4, new byte[0], req4));
        Ipv4Packet reply4 = Ipv4Codec.parse(p.process(wire4).getFirst());
        assertEquals(0, IcmpCodec.parseV4(reply4.payload()).type());
        byte[] req6 = IcmpCodec.serializeV6(new IcmpMessage(128, 0, 3, new byte[]{8}), remote6, local6);
        byte[] wire6 = Ipv6Codec.serialize(new Ipv6Packet(0, 0, 58, 64, remote6, local6, req6));
        Ipv6Packet reply6 = Ipv6Codec.parse(p.process(wire6).getFirst());
        assertEquals(129, IcmpCodec.parseV6(reply6.payload(), local6, remote6).type());
    }
    @Test void deliversUdpAndRejectsClosedPort() {
        var p = processor(); var received = new AtomicReference<UdpReceivedDatagram>(); p.bindUdp(9000, received::set);
        byte[] udp = UdpCodec.serialize(new UdpDatagram(8000, 9000, "hi".getBytes()), remote6, local6);
        assertTrue(p.process(Ipv6Codec.serialize(new Ipv6Packet(0,0,17,64,remote6,local6,udp))).isEmpty());
        assertArrayEquals("hi".getBytes(), received.get().payload());
        byte[] closed = UdpCodec.serialize(new UdpDatagram(8000, 9001, new byte[0]), remote4, local4);
        byte[] original = Ipv4Codec.serialize(new Ipv4Packet(0,1,2,0,64,17,remote4,local4,new byte[0],closed));
        Ipv4Packet response = Ipv4Codec.parse(p.process(original).getFirst());
        IcmpMessage unreachable = IcmpCodec.parseV4(response.payload());
        assertEquals(3, unreachable.type()); assertEquals(3, unreachable.code());
    }
    @Test void performsTcpPassiveOpenOverIpv6() {
        var p = processor(); p.listen(8080);
        TcpSegment syn = new TcpSegment(50000, 8080, 10, 0, TcpFlags.SYN, 65535, 0, new byte[0], new byte[0]);
        byte[] tcp = TcpCodec.serialize(syn, remote6, local6);
        byte[] request = Ipv6Codec.serialize(new Ipv6Packet(0,0,6,64,remote6,local6,tcp));
        Ipv6Packet response = Ipv6Codec.parse(p.process(request).getFirst());
        TcpSegment synAck = TcpCodec.parse(response.payload(), local6, remote6);
        assertTrue(synAck.has(TcpFlags.SYN)); assertTrue(synAck.has(TcpFlags.ACK)); assertEquals(11, synAck.acknowledgementNumber());
    }
}
