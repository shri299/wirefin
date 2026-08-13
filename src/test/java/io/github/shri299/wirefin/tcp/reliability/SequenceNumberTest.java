package io.github.shri299.wirefin.tcp.reliability;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SequenceNumberTest {
    @Test void wrapsAndComparesAcrossZero() {
        assertEquals(1, SequenceNumber.add(0xffff_ffffL, 2));
        assertTrue(SequenceNumber.lessThan(0xffff_ffffL, 1));
        assertTrue(SequenceNumber.greaterThan(1, 0xffff_ffffL));
        assertEquals(2, SequenceNumber.distance(0xffff_ffffL, 1));
    }
}
