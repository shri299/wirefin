package io.github.shri299.wirefin.tcp.congestion;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CubicCongestionControllerTest {
    @Test void growsWithTimeAndRetainsBoundedRecoverySemantics() {
        var cubic = new CubicCongestionController(1000);
        cubic.onAcknowledgement(1000, 0);
        assertEquals(2000, cubic.congestionWindow()); // slow start

        cubic.onFastRetransmit(10_000, 123);
        assertTrue(cubic.inFastRecovery());
        assertEquals(7000, cubic.slowStartThreshold());
        assertEquals(10_000, cubic.congestionWindow());
        assertEquals(123, cubic.recoveryPoint());
        cubic.onRecoveryComplete();
        long before = cubic.congestionWindow();
        cubic.onAcknowledgement(1000, 0);
        cubic.onAcknowledgement(1000, 10_000_000_000L);
        assertTrue(cubic.congestionWindow() > before);

        cubic.onTimeout(cubic.congestionWindow());
        assertEquals(1000, cubic.congestionWindow());
        assertTrue(cubic.slowStartThreshold() >= 2000);
        assertFalse(cubic.inFastRecovery());
    }

    @Test void parsesOnlyAdvertisedAlgorithms() {
        assertEquals(CongestionControlAlgorithm.RENO, CongestionControlAlgorithm.parse("reno"));
        assertEquals(CongestionControlAlgorithm.CUBIC, CongestionControlAlgorithm.parse("CUBIC"));
        assertThrows(IllegalArgumentException.class, () -> CongestionControlAlgorithm.parse("bbr"));
    }
}
