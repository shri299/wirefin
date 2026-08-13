package io.github.shri299.wirefin.tcp.reliability;

import io.github.shri299.wirefin.tcp.TcpFlags;
import io.github.shri299.wirefin.tcp.TcpSegment;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class RetransmissionManagerTest {
    @Test void estimatesRttFromFirstAndSubsequentSamples() {
        var estimator = new RtoEstimator(Duration.ofSeconds(1), Duration.ofMillis(1), Duration.ofSeconds(60));
        estimator.sample(Duration.ofMillis(100).toNanos());
        assertEquals(Duration.ofMillis(100).toNanos(), estimator.smoothedRttNanos());
        assertEquals(Duration.ofMillis(50).toNanos(), estimator.rttVariationNanos());
        assertEquals(Duration.ofMillis(300).toNanos(), estimator.rtoNanos());
        estimator.sample(Duration.ofMillis(120).toNanos());
        assertEquals(Duration.ofMillis(102).toNanos() + 500_000, estimator.smoothedRttNanos());
        assertEquals(Duration.ofMillis(42).toNanos() + 500_000, estimator.rttVariationNanos());
    }

    @Test void timeoutBacksOffAndKarnSuppressesRetransmittedSample() {
        var estimator = new RtoEstimator(Duration.ofMillis(100), Duration.ofMillis(10), Duration.ofSeconds(2));
        var manager = new RetransmissionManager(estimator);
        TcpSegment segment = data(100, new byte[]{1,2,3});
        manager.track(segment, 0);
        assertTrue(manager.due(99_999_999).isEmpty());
        assertEquals(segment, manager.due(100_000_000).getFirst());
        assertEquals(Duration.ofMillis(200).toNanos(), manager.rtoNanos());
        manager.acknowledge(103, Duration.ofMillis(150).toNanos());
        assertEquals(-1, estimator.smoothedRttNanos(), "Karn: retransmitted data must not sample RTT");
    }

    @Test void partialCumulativeAckTrimsBytesInFlight() {
        var manager = new RetransmissionManager(Duration.ofSeconds(1));
        manager.track(data(100, new byte[]{1,2,3,4}), 0);
        assertEquals(2, manager.acknowledge(102, 10).newlyAcknowledgedBytes());
        assertEquals(2, manager.bytesInFlight());
        assertEquals(2, manager.fastRetransmit(20).payload().length);
    }

    private static TcpSegment data(long sequence, byte[] payload) {
        return new TcpSegment(1, 2, sequence, 20, TcpFlags.ACK, 10, 0, new byte[0], payload);
    }
}
