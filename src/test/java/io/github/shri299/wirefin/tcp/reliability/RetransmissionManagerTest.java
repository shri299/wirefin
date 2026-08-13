package io.github.shri299.wirefin.tcp.reliability;

import io.github.shri299.wirefin.tcp.TcpFlags;
import io.github.shri299.wirefin.tcp.TcpSegment;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class RetransmissionManagerTest {
    @Test void retransmitsOnDeadlineAndRemovesOnCumulativeAck() {
        var manager = new RetransmissionManager(Duration.ofMillis(100));
        var segment = new TcpSegment(1, 2, 100, 20, TcpFlags.ACK, 10, 0, new byte[0], new byte[]{1,2,3});
        manager.track(segment, 1_000_000);
        assertTrue(manager.due(100_999_999).isEmpty());
        assertEquals(segment, manager.due(101_000_000).getFirst());
        assertEquals(3, manager.acknowledge(103));
        assertEquals(0, manager.size());
    }
}
