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

    @Test void orderingIsConsistentAcrossManyWrapBoundaryPairs() {
        long base = 0xffff_fff0L;
        for (int i = 0; i < 64; i++) {
            long later = SequenceNumber.add(base, i);
            assertEquals(i, SequenceNumber.distance(base, later));
            if (i > 0) assertTrue(SequenceNumber.lessThan(base, later));
        }
    }
}
