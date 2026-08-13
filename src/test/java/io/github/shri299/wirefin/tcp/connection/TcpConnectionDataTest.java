package io.github.shri299.wirefin.tcp.connection;

import io.github.shri299.wirefin.ipv4.Ipv4Address;
import io.github.shri299.wirefin.tcp.TcpFlags;
import io.github.shri299.wirefin.tcp.TcpSegment;
import io.github.shri299.wirefin.tcp.state.TcpState;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TcpConnectionDataTest {
    private static final TcpConnectionKey KEY = new TcpConnectionKey(Ipv4Address.parse("10.0.0.2"), 8080,
            Ipv4Address.parse("10.0.0.1"), 50000);

    @Test void acknowledgesWritesAndReassemblesOutOfOrderData() throws Exception {
        TcpConnection connection = established(10_000);
        var sent = connection.send("response".getBytes(), 0);
        assertEquals(8, connection.bytesInFlight());
        connection.receive(segment(501, 10_009, TcpFlags.ACK, new byte[0]));
        assertEquals(0, connection.bytesInFlight());

        connection.receive(segment(504, 10_009, TcpFlags.ACK, "def".getBytes()));
        assertEquals(501, connection.receiveNext());
        connection.receive(segment(501, 10_009, TcpFlags.ACK, "abc".getBytes()));
        assertArrayEquals("abc".getBytes(), connection.read());
        assertArrayEquals("def".getBytes(), connection.read());
        assertEquals(507, connection.receiveNext());
    }

    @Test void enforcesRemoteReceiveWindowBeforeMutatingSequenceState() {
        TcpConnection connection = established(100);
        long before = connection.sendNext();
        assertThrows(IllegalStateException.class, () -> connection.send(new byte[101], 0));
        assertEquals(before, connection.sendNext());
    }

    @Test void performsActiveCloseAndEntersTimeWait() {
        TcpConnection connection = established(1000);
        TcpSegment fin = connection.close(0).getFirst();
        assertEquals(TcpState.FIN_WAIT_1, connection.state());
        connection.receive(segment(501, fin.sequenceNumber() + 1, TcpFlags.ACK, new byte[0]));
        assertEquals(TcpState.FIN_WAIT_2, connection.state());
        connection.receive(segment(501, fin.sequenceNumber() + 1, TcpFlags.FIN | TcpFlags.ACK, new byte[0]));
        assertEquals(TcpState.TIME_WAIT, connection.state());
    }

    private static TcpConnection established(int window) {
        var syn = segment(500, 0, TcpFlags.SYN, new byte[0], window);
        TcpConnection connection = TcpConnection.passiveOpen(KEY, 10_000, syn);
        connection.receive(segment(501, 10_001, TcpFlags.ACK, new byte[0], window));
        return connection;
    }
    private static TcpSegment segment(long sequence, long ack, int flags, byte[] payload) {
        return segment(sequence, ack, flags, payload, 32768);
    }
    private static TcpSegment segment(long sequence, long ack, int flags, byte[] payload, int window) {
        return new TcpSegment(50000, 8080, sequence, ack, flags, window, 0, new byte[0], payload);
    }
}
