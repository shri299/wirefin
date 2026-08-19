package io.github.shri299.wirefin.memory;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class PacketMemoryTest {
    @Test void slicesShareStorageAndReleaseItAfterTheLastOwner() {
        AtomicInteger releases = new AtomicInteger();
        PacketMemory root = PacketMemory.takeOwnership(ByteBuffer.wrap(new byte[] {1, 2, 3, 4}), 0, 4,
                releases::incrementAndGet);
        PacketMemory slice = root.slice(1, 2);

        assertEquals(0x0203, slice.getUnsignedShort(0));
        root.close();
        assertEquals(0, releases.get());
        assertEquals(3, slice.getUnsignedByte(1));
        slice.close();
        assertEquals(1, releases.get());
    }

    @Test void rejectsUseAfterReleaseDoubleReleaseAndInvalidRanges() {
        PacketMemory memory = PacketMemory.copyOf(new byte[] {1, 2, 3});
        assertThrows(IndexOutOfBoundsException.class, () -> memory.get(3));
        assertThrows(IndexOutOfBoundsException.class, () -> memory.slice(2, 2));
        memory.close();
        assertThrows(IllegalStateException.class, memory::length);
        assertThrows(IllegalStateException.class, () -> memory.get(0));
        assertThrows(IllegalStateException.class, memory::close);
    }

    @Test void directMemorySupportsEndianReadsAndDefensiveCopies() {
        try (PacketMemory memory = PacketMemory.allocateDirect(4)) {
            assertArrayEquals(new byte[4], memory.copyToArray());
        }
        byte[] source = {1, 2};
        try (PacketMemory memory = PacketMemory.copyOf(source)) {
            source[0] = 9;
            assertEquals(0x0102, memory.getUnsignedShort(0));
        }
    }
}
