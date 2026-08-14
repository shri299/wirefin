package io.github.shri299.wirefin.runtime;

import io.github.shri299.wirefin.ipv4.Ipv4Address;
import io.github.shri299.wirefin.ipv4.Ipv4Codec;
import io.github.shri299.wirefin.ipv4.Ipv4Packet;
import io.github.shri299.wirefin.tcp.TcpCodec;
import io.github.shri299.wirefin.tcp.TcpFlags;
import io.github.shri299.wirefin.tcp.TcpSegment;
import io.github.shri299.wirefin.tcp.connection.TcpConnection;
import io.github.shri299.wirefin.tcp.state.TcpState;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class AdvancedPacketProcessorTest {
    private static final Ipv4Address CLIENT = Ipv4Address.parse("10.0.0.1");
    private static final Ipv4Address SERVER = Ipv4Address.parse("10.0.0.2");

    @Test void activeOpenUsesEphemeralPortAndValidatesSynAck() {
        List<byte[]> output = new ArrayList<>();
        PacketProcessor processor = new PacketProcessor(CLIENT, () -> 1000, output::add,
                () -> 0, config());
        TcpConnection connection = processor.connect(SERVER, 8080);
        TcpSegment syn = decode(output.getFirst(), CLIENT, SERVER);
        assertEquals(TcpState.SYN_SENT, connection.state());
        assertTrue(syn.sourcePort() >= 49_152);

        TcpSegment synAck = new TcpSegment(8080, syn.sourcePort(), 9000, 1001,
                TcpFlags.SYN | TcpFlags.ACK, 4000, 0, new byte[0], new byte[0]);
        TcpSegment ack = decode(processor.process(packet(synAck, SERVER, CLIENT)).getFirst(), CLIENT, SERVER);
        assertEquals(9001, ack.acknowledgementNumber());
        assertEquals(TcpState.ESTABLISHED, connection.state());
    }

    @Test void activeOpenHandlesRefusalAndRetransmitsSyn() {
        AtomicLong now = new AtomicLong();
        List<byte[]> output = new ArrayList<>();
        PacketProcessor processor = new PacketProcessor(CLIENT, () -> 1000, output::add, now::get, config());
        TcpConnection connection = processor.connect(SERVER, 8080);
        TcpSegment syn = decode(output.getFirst(), CLIENT, SERVER);
        now.set(10);
        processor.pollRetransmissions(10);
        assertEquals(2, output.size());
        assertTrue(decode(output.get(1), CLIENT, SERVER).has(TcpFlags.SYN));

        TcpSegment reset = new TcpSegment(8080, syn.sourcePort(), 0, 1001,
                TcpFlags.RST | TcpFlags.ACK, 0, 0, new byte[0], new byte[0]);
        processor.process(packet(reset, SERVER, CLIENT));
        assertEquals(TcpState.CLOSED, connection.state());
        assertEquals(0, processor.connections().size());
    }

    @Test void backlogSeparatesHalfOpenAndUnacceptedConnections() {
        List<TcpConnection> accepted = new ArrayList<>();
        PacketProcessor processor = new PacketProcessor(SERVER, () -> 1000);
        processor.listen(8080, 1, false, accepted::add);
        TcpSegment first = syn(50_000, 100);
        assertEquals(1, processor.process(packet(first, CLIENT, SERVER)).size());
        assertEquals(1, processor.halfOpenCount(8080));
        assertTrue(processor.process(packet(syn(50_001, 200), CLIENT, SERVER)).isEmpty());

        processor.process(packet(ack(50_000, 101, 1001), CLIENT, SERVER));
        assertEquals(0, processor.halfOpenCount(8080));
        assertEquals(1, processor.establishedBacklogCount(8080));
        assertEquals(1, accepted.size());
        assertEquals(1, processor.process(packet(syn(50_001, 200), CLIENT, SERVER)).size());
        assertEquals(1, processor.halfOpenCount(8080));
        assertTrue(decode(processor.process(packet(ack(50_001, 201, 1001), CLIENT, SERVER)).getFirst(),
                SERVER, CLIENT).has(TcpFlags.RST));
        assertEquals(1, processor.establishedBacklogCount(8080));
        processor.accepted(accepted.getFirst().key());
        assertEquals(1, processor.process(packet(syn(50_001, 200), CLIENT, SERVER)).size());
    }

    @Test void synCookieCompletesHandshakeWithoutAllocatingOnSyn() {
        List<TcpConnection> accepted = new ArrayList<>();
        PacketProcessor processor = new PacketProcessor(SERVER, () -> 1000);
        processor.listen(8080, 1, true, accepted::add);
        processor.process(packet(syn(50_000, 100), CLIENT, SERVER)); // occupy normal half-open slot
        TcpSegment cookieSynAck = decode(processor.process(packet(syn(50_001, 200), CLIENT, SERVER)).getFirst(), SERVER, CLIENT);
        assertEquals(1, processor.connections().size());
        assertTrue(cookieSynAck.has(TcpFlags.SYN));

        processor.process(packet(ack(50_001, 201, cookieSynAck.sequenceNumber() + 1), CLIENT, SERVER));
        assertEquals(2, processor.connections().size());
        assertEquals(1, accepted.size());
        assertEquals(TcpState.ESTABLISHED, accepted.getFirst().state());
    }

    private static TcpConnection.Config config() {
        return new TcpConnection.Config(1024, 1400, 536, Duration.ofNanos(10), Duration.ofNanos(1),
                Duration.ofSeconds(1), Duration.ofSeconds(1));
    }
    private static TcpSegment syn(int port, long sequence) {
        return new TcpSegment(port, 8080, sequence, 0, TcpFlags.SYN, 1000, 0, new byte[0], new byte[0]);
    }
    private static TcpSegment ack(int port, long sequence, long acknowledgement) {
        return new TcpSegment(port, 8080, sequence, acknowledgement, TcpFlags.ACK, 1000, 0, new byte[0], new byte[0]);
    }
    private static byte[] packet(TcpSegment segment, Ipv4Address source, Ipv4Address destination) {
        byte[] tcp = TcpCodec.serialize(segment, source, destination);
        return Ipv4Codec.serialize(new Ipv4Packet(0, 1, 2, 0, 64, 6, source, destination, new byte[0], tcp));
    }
    private static TcpSegment decode(byte[] packet, Ipv4Address source, Ipv4Address destination) {
        Ipv4Packet ip = Ipv4Codec.parse(packet);
        return TcpCodec.parse(ip.payload(), source, destination);
    }
}
