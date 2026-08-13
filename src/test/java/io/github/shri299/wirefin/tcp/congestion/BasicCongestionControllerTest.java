package io.github.shri299.wirefin.tcp.congestion;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BasicCongestionControllerTest {
    @Test void growsOnAckAndCollapsesOnLoss() {
        var controller = new BasicCongestionController(1000);
        controller.onAcknowledgement(1000);
        assertEquals(2000, controller.congestionWindow());
        controller.onLoss();
        assertEquals(1000, controller.congestionWindow());
        assertEquals(2000, controller.slowStartThreshold());
    }
}
