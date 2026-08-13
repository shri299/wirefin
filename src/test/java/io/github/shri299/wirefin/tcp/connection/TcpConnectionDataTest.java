package io.github.shri299.wirefin.tcp.connection;

import io.github.shri299.wirefin.ipv4.Ipv4Address;
import io.github.shri299.wirefin.tcp.TcpFlags;
import io.github.shri299.wirefin.tcp.TcpOptions;
import io.github.shri299.wirefin.tcp.TcpSegment;
import io.github.shri299.wirefin.tcp.state.TcpState;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class TcpConnectionDataTest {
    private static final TcpConnectionKey KEY = new TcpConnectionKey(Ipv4Address.parse("10.0.0.2"), 8080,
            Ipv4Address.parse("10.0.0.1"), 50000);

    @Test void negotiatesMssAndQueuesLargeWriteAcrossAcknowledgements() {
        TcpConnection connection = established(32, 4, 1000);
        assertEquals(4, connection.sendMss());
        var first = connection.send(bytes("abcdefghij"), 10);
        assertEquals(1, first.size());
        assertEquals(4, first.getFirst().payload().length);
        assertEquals(6, connection.pendingSendBytes());
        var next = connection.receive(segment(501, 10_005, TcpFlags.ACK, new byte[0]), 20).outbound();
        assertEquals(2, next.size());
        assertEquals(4, next.get(0).payload().length);
        assertEquals(2, next.get(1).payload().length);
    }

    @Test void defaultsPeerMssWhenSynHasNoMssOption() {
        TcpConnection connection = established(1024, null, 1000);
        assertEquals(536, connection.sendMss());
    }

    @Test void thirdDuplicateAckFastRetransmitsOldestSegment() {
        TcpConnection connection = established(1024, 100, 1000);
        TcpSegment sent = connection.send(bytes("lost"), 10).getFirst();
        for (int i = 1; i < 3; i++) assertTrue(connection.receive(segment(501, 10_001, TcpFlags.ACK, new byte[0]), 20 + i).outbound().isEmpty());
        assertEquals(sent.sequenceNumber(), connection.receive(segment(501, 10_001, TcpFlags.ACK, new byte[0]), 23)
                .outbound().getFirst().sequenceNumber());
    }

    @Test void duplicateSynRetransmitsSynAckAndInvalidHandshakeAckResets() {
        TcpSegment syn = segment(500, 0, TcpFlags.SYN, new byte[0]);
        TcpConnection connection = TcpConnection.passiveOpen(KEY, 10_000, syn, 0, config(32));
        assertTrue(connection.receive(syn, 1).outbound().getFirst().has(TcpFlags.SYN));
        var invalid = connection.receive(segment(999, 10_001, TcpFlags.ACK, new byte[0]), 2);
        assertTrue(invalid.outbound().getFirst().has(TcpFlags.RST));
        assertEquals(TcpState.CLOSED, connection.state());
    }

    @Test void peerCloseThenApplicationCloseUsesCloseWaitAndLastAck() throws Exception {
        TcpConnection connection = established(32, 16, 1000);
        connection.receive(segment(501, 10_001, TcpFlags.FIN | TcpFlags.ACK, new byte[0]), 10);
        assertEquals(TcpState.CLOSE_WAIT, connection.state());
        assertEquals(-1, connection.read(new byte[1], 0, 1));
        TcpSegment fin = connection.close(20).getFirst();
        assertEquals(TcpState.LAST_ACK, connection.state());
        connection.receive(segment(502, fin.sequenceNumber() + 1, TcpFlags.ACK, new byte[0]), 30);
        assertEquals(TcpState.CLOSED, connection.state());
    }

    @Test void simultaneousCloseAndTimeWaitExpiryAreTimerDriven() {
        TcpConnection connection = established(32, 16, 1000);
        TcpSegment fin = connection.close(10).getFirst();
        connection.receive(segment(501, fin.sequenceNumber(), TcpFlags.FIN | TcpFlags.ACK, new byte[0]), 20);
        assertEquals(TcpState.CLOSING, connection.state());
        connection.receive(segment(502, fin.sequenceNumber() + 1, TcpFlags.ACK, new byte[0]), 30);
        assertEquals(TcpState.TIME_WAIT, connection.state());
        assertFalse(connection.expireTimeWait(39));
        assertTrue(connection.expireTimeWait(40));
        assertEquals(TcpState.CLOSED, connection.state());
    }

    @Test void dataAndSequenceTrackingCrossUint32Wrap() throws Exception {
        TcpSegment syn = segment(0xffff_fff0L, 0, TcpFlags.SYN, new byte[0]);
        TcpConnection connection = TcpConnection.passiveOpen(KEY, 0xffff_fffcL, syn, 0, config(32));
        connection.receive(segment(0xffff_fff1L, 0xffff_fffdL, TcpFlags.ACK, new byte[0]), 1);
        connection.receive(segment(0xffff_fff1L, 0xffff_fffdL, TcpFlags.ACK, bytes("0123456789abcdef")), 2);
        assertEquals(1, connection.receiveNext());
        byte[] data = new byte[16];
        assertEquals(16, connection.read(data, 0, 16));
    }

    private static TcpConnection established(int capacity, Integer mss, int window) {
        byte[] options = mss == null ? new byte[0] : TcpOptions.mss(mss);
        TcpSegment syn = new TcpSegment(50000, 8080, 500, 0, TcpFlags.SYN, window, 0, options, new byte[0]);
        TcpConnection connection = TcpConnection.passiveOpen(KEY, 10_000, syn, 0, config(capacity));
        connection.receive(segment(501, 10_001, TcpFlags.ACK, new byte[0], window), 1);
        return connection;
    }
    private static TcpConnection.Config config(int capacity) {
        return new TcpConnection.Config(capacity, 1400, 536, Duration.ofSeconds(1), Duration.ofMillis(1),
                Duration.ofSeconds(60), Duration.ofNanos(10));
    }
    private static TcpSegment segment(long sequence, long ack, int flags, byte[] payload) {
        return segment(sequence, ack, flags, payload, 32768);
    }
    private static TcpSegment segment(long sequence, long ack, int flags, byte[] payload, int window) {
        return new TcpSegment(50000, 8080, sequence, ack, flags, window, 0, new byte[0], payload);
    }
    private static byte[] bytes(String text) { return text.getBytes(java.nio.charset.StandardCharsets.US_ASCII); }
}
