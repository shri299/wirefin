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

    @Test void generatedOrderingRemainsConsistentWithinTheUnambiguousHalfSpace() {
        var random = new java.util.Random(0x53455155454e4345L);
        for (int example = 0; example < 100_000; example++) {
            long base = Integer.toUnsignedLong(random.nextInt());
            int delta = random.nextInt(Integer.MAX_VALUE);
            long later = SequenceNumber.add(base, delta);
            assertEquals(delta, SequenceNumber.distance(base, later));
            if (delta == 0) assertFalse(SequenceNumber.lessThan(base, later));
            else {
                assertTrue(SequenceNumber.lessThan(base, later));
                assertTrue(SequenceNumber.greaterThan(later, base));
            }
        }
    }
}
