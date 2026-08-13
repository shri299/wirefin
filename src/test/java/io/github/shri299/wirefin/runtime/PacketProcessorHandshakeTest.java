package io.github.shri299.wirefin.runtime;

import io.github.shri299.wirefin.ipv4.*;
import io.github.shri299.wirefin.tcp.*;
import io.github.shri299.wirefin.tcp.state.TcpState;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PacketProcessorHandshakeTest {
    private static final Ipv4Address CLIENT = Ipv4Address.parse("10.0.0.1");
    private static final Ipv4Address SERVER = Ipv4Address.parse("10.0.0.2");

    @Test void completesPassiveHandshake() {
        var processor = new PacketProcessor(SERVER, () -> 1000);
        processor.listen(8080);
        TcpSegment syn = new TcpSegment(50000, 8080, 2000, 0, TcpFlags.SYN, 32768, 0, new byte[0], new byte[0]);
        byte[] synAckPacket = processor.process(packet(syn, CLIENT, SERVER)).getFirst();
        Ipv4Packet ip = Ipv4Codec.parse(synAckPacket);
        TcpSegment synAck = TcpCodec.parse(ip.payload(), SERVER, CLIENT);
        assertEquals(TcpFlags.SYN | TcpFlags.ACK, synAck.flags());
        assertEquals(1000, synAck.sequenceNumber());
        assertEquals(2001, synAck.acknowledgementNumber());

        TcpSegment ack = new TcpSegment(50000, 8080, 2001, 1001, TcpFlags.ACK, 32768, 0, new byte[0], new byte[0]);
        assertTrue(processor.process(packet(ack, CLIENT, SERVER)).isEmpty());
        assertEquals(TcpState.ESTABLISHED, processor.connections().snapshot().iterator().next().state());
    }

    private static byte[] packet(TcpSegment segment, Ipv4Address source, Ipv4Address destination) {
        byte[] tcp = TcpCodec.serialize(segment, source, destination);
        return Ipv4Codec.serialize(new Ipv4Packet(0, 1, 2, 0, 64, 6, source, destination, new byte[0], tcp));
    }
}
