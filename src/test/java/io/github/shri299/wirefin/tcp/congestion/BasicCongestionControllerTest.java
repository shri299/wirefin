package io.github.shri299.wirefin.tcp.congestion;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BasicCongestionControllerTest {
    @Test void distinguishesTimeoutFromFastRetransmitLoss() {
        var controller = new BasicCongestionController(1000);
        controller.onAcknowledgement(1000);
        assertEquals(2000, controller.congestionWindow());
        controller.onFastRetransmit(8000, 9000);
        assertEquals(7000, controller.congestionWindow());
        assertTrue(controller.inFastRecovery());
        controller.onDuplicateAck();
        assertEquals(8000, controller.congestionWindow());
        controller.onPartialAcknowledgement(1000);
        assertEquals(7000, controller.congestionWindow());
        controller.onRecoveryComplete();
        assertEquals(4000, controller.congestionWindow());
        controller.onTimeout(8000);
        assertEquals(1000, controller.congestionWindow());
        assertEquals(4000, controller.slowStartThreshold());
    }
}
