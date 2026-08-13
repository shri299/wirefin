package io.github.shri299.wirefin.runtime;

import io.github.shri299.wirefin.ipv4.*;
import io.github.shri299.wirefin.tcp.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PacketProcessorEdgeTest {
    private static final Ipv4Address CLIENT = Ipv4Address.parse("10.0.0.1");
    private static final Ipv4Address SERVER = Ipv4Address.parse("10.0.0.2");

    @Test void closedPortReturnsResetAndMalformedPacketIsDropped() {
        PacketProcessor processor = new PacketProcessor(SERVER, () -> 1000);
        TcpSegment syn = new TcpSegment(50000, 9999, 50, 0, TcpFlags.SYN, 1000, 0, new byte[0], new byte[0]);
        TcpSegment reset = response(processor.process(packet(syn)).getFirst());
        assertTrue(reset.has(TcpFlags.RST));
        assertTrue(reset.has(TcpFlags.ACK));
        assertEquals(51, reset.acknowledgementNumber());
        assertTrue(processor.process(new byte[]{1,2,3}).isEmpty());
    }

    private static byte[] packet(TcpSegment segment) {
        byte[] tcp = TcpCodec.serialize(segment, CLIENT, SERVER);
        return Ipv4Codec.serialize(new Ipv4Packet(0, 1, 2, 0, 64, 6, CLIENT, SERVER, new byte[0], tcp));
    }
    private static TcpSegment response(byte[] packet) {
        Ipv4Packet ip = Ipv4Codec.parse(packet);
        return TcpCodec.parse(ip.payload(), SERVER, CLIENT);
    }
}
