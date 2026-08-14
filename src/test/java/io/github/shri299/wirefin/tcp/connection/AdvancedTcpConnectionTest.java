package io.github.shri299.wirefin.tcp.connection;

import io.github.shri299.wirefin.ipv4.Ipv4Address;
import io.github.shri299.wirefin.tcp.TcpFlags;
import io.github.shri299.wirefin.tcp.TcpOptions;
import io.github.shri299.wirefin.tcp.TcpSegment;
import io.github.shri299.wirefin.tcp.state.TcpState;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class AdvancedTcpConnectionTest {
    private static final TcpConnectionKey KEY = new TcpConnectionKey(Ipv4Address.parse("10.0.0.2"), 8080,
            Ipv4Address.parse("10.0.0.1"), 50000);

    @Test void negotiatesScalingTimestampsAndSackWithoutScalingSynWindow() {
        TcpSegment syn = segment(500, 0, TcpFlags.SYN, 60_000,
                TcpOptions.syn(1200, 3, true, 123L), new byte[0]);
        TcpConnection connection = TcpConnection.passiveOpen(KEY, 10_000, syn, 1_000_000, config(1_048_576));
        TcpSegment synAck = connection.synAck();
        var options = TcpOptions.parse(synAck.options());
        assertEquals(4, options.windowScale().orElseThrow());
        assertTrue(options.sackPermitted());
        assertEquals(123, options.timestamp().orElseThrow().echoReply());
        assertEquals(65_535, synAck.windowSize());

        connection.receive(segment(501, 10_001, TcpFlags.ACK, 100,
                TcpOptions.established(new TcpOptions.Timestamp(124, options.timestamp().orElseThrow().value()), List.of()),
                new byte[0]), 2_000_000);
        assertTrue(connection.windowScalingNegotiated());
        assertEquals(3, connection.peerWindowScale());
        assertEquals(4, connection.localWindowScale());
        assertEquals(800, connection.remoteWindow());
        assertTrue(connection.timestampsNegotiated());
        assertTrue(connection.sackPermitted());
        assertEquals(65_535, connection.ack().windowSize());
    }

    @Test void peerWithoutWindowScaleUsesWireWindowDirectly() {
        TcpConnection connection = established(config(1024), new byte[0], 321);
        assertFalse(connection.windowScalingNegotiated());
        assertEquals(321, connection.remoteWindow());
    }

    @Test void echoesTimestampAndReportsOutOfOrderSackBlock() {
        TcpConnection connection = established(config(1024), TcpOptions.syn(100, 2, true, 10L), 1000);
        TcpSegment reply = connection.receive(segment(505, 10_001, TcpFlags.ACK, 1000,
                TcpOptions.established(new TcpOptions.Timestamp(777, 0), List.of()), bytes("cd")), 20_000_000)
                .outbound().getFirst();
        var options = TcpOptions.parse(reply.options());
        assertEquals(777, options.timestamp().orElseThrow().echoReply());
        assertEquals(List.of(new TcpOptions.SackBlock(505, 507)), options.sackBlocks());
    }

    @Test void persistProbePreservesQueueAndWindowUpdateResumesTransmission() {
        TcpConnection connection = established(config(128), new byte[0], 0);
        assertTrue(connection.send(bytes("queued"), 10).isEmpty());
        assertEquals(6, connection.pendingSendBytes());
        assertTrue(connection.retransmissionsDue(10).isEmpty());
        TcpSegment probe = connection.retransmissionsDue(11).getFirst();
        assertArrayEquals(bytes("q"), probe.payload());
        assertEquals(6, connection.pendingSendBytes());
        List<TcpSegment> resumed = connection.receive(segment(501, 10_001, TcpFlags.ACK, 32,
                new byte[0], new byte[0]), 12).outbound();
        assertEquals(1, resumed.size());
        assertArrayEquals(bytes("queued"), resumed.getFirst().payload());
        assertEquals(0, connection.pendingSendBytes());
    }

    @Test void newRenoPartialAckRetransmitsNextLossAndFullAckExitsRecovery() {
        TcpConnection connection = established(config(1024), TcpOptions.mss(4), 1000);
        connection.send(bytes("aaaa"), 10);
        connection.receive(segment(501, 10_005, TcpFlags.ACK, 1000, new byte[0], new byte[0]), 20);
        connection.send(bytes("bbbbcccc"), 30);
        for (int i = 0; i < 2; i++)
            assertTrue(connection.receive(segment(501, 10_005, TcpFlags.ACK, 1000, new byte[0], new byte[0]), 40 + i).outbound().isEmpty());
        assertEquals(10_005, connection.receive(segment(501, 10_005, TcpFlags.ACK, 1000,
                new byte[0], new byte[0]), 42).outbound().getFirst().sequenceNumber());
        assertTrue(connection.inFastRecovery());
        assertEquals(10_013, connection.recoveryPoint());

        TcpSegment secondLoss = connection.receive(segment(501, 10_009, TcpFlags.ACK, 1000,
                new byte[0], new byte[0]), 50).outbound().getFirst();
        assertEquals(10_009, secondLoss.sequenceNumber());
        assertTrue(connection.inFastRecovery());
        connection.receive(segment(501, 10_013, TcpFlags.ACK, 1000, new byte[0], new byte[0]), 60);
        assertFalse(connection.inFastRecovery());
    }

    @Test void onlyExactSequenceResetClosesEstablishedConnection() {
        TcpConnection connection = established(config(1024), new byte[0], 1000);
        assertTrue(connection.receive(segment(2000, 0, TcpFlags.RST, 0, new byte[0], new byte[0]), 10).outbound().isEmpty());
        assertEquals(TcpState.ESTABLISHED, connection.state());
        assertTrue(connection.receive(segment(502, 0, TcpFlags.RST, 0, new byte[0], new byte[0]), 11)
                .outbound().getFirst().has(TcpFlags.ACK));
        assertEquals(TcpState.ESTABLISHED, connection.state());
        connection.receive(segment(501, 0, TcpFlags.RST, 0, new byte[0], new byte[0]), 12);
        assertEquals(TcpState.CLOSED, connection.state());
    }

    @Test void scaledWindowAndReceiveSequenceRemainCorrectAcrossWrap() {
        TcpSegment syn = segment(0xffff_fffeL, 0, TcpFlags.SYN, 100,
                TcpOptions.syn(100, 3, false, null), new byte[0]);
        TcpConnection connection = TcpConnection.passiveOpen(KEY, 10_000, syn, 0, config(4096));
        connection.receive(segment(0xffff_ffffL, 10_001, TcpFlags.ACK, 100,
                new byte[0], new byte[0]), 1);
        assertEquals(800, connection.remoteWindow());
        connection.receive(segment(0xffff_ffffL, 10_001, TcpFlags.ACK, 100,
                new byte[0], bytes("ab")), 2);
        assertEquals(1, connection.receiveNext());
    }

    private static TcpConnection established(TcpConnection.Config config, byte[] synOptions, int window) {
        TcpConnection connection = TcpConnection.passiveOpen(KEY, 10_000,
                segment(500, 0, TcpFlags.SYN, window, synOptions, new byte[0]), 0, config);
        byte[] ackOptions = TcpOptions.parse(synOptions).timestamp()
                .map(ts -> TcpOptions.established(new TcpOptions.Timestamp(ts.value() + 1, 0), List.of()))
                .orElse(new byte[0]);
        connection.receive(segment(501, 10_001, TcpFlags.ACK, window, ackOptions, new byte[0]), 1);
        return connection;
    }
    private static TcpConnection.Config config(int capacity) {
        return new TcpConnection.Config(capacity, 1400, 536, Duration.ofNanos(10), Duration.ofNanos(1),
                Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofNanos(1), Duration.ofNanos(8),
                true, 4, true, true, 4);
    }
    private static TcpSegment segment(long sequence, long ack, int flags, int window, byte[] options, byte[] payload) {
        return new TcpSegment(50000, 8080, sequence, ack, flags, window, 0, options, payload);
    }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.US_ASCII); }
}
