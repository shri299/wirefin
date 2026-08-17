package io.github.shri299.wirefin.tcp.connection;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ReceiveBufferPropertyTest {
    @Test void randomSegmentationOrderingOverlapAndDuplicatesPreserveTheStream() throws Exception {
        Random random = new Random(0x5245415353454dL);
        for (int example = 0; example < 300; example++) {
            byte[] original = new byte[1 + random.nextInt(512)];
            random.nextBytes(original);
            long initial = Integer.toUnsignedLong(random.nextInt());
            ReceiveBuffer buffer = new ReceiveBuffer(initial, original.length + 64);
            List<Part> parts = segment(random, original);
            parts.addAll(new ArrayList<>(parts));
            if (original.length > 4) parts.add(new Part(1, Arrays.copyOfRange(original, 1, original.length - 1)));
            Collections.shuffle(parts, random);
            for (Part part : parts) buffer.accept(initial + part.offset, part.bytes);

            byte[] delivered = new byte[original.length];
            assertEquals(original.length, buffer.read(delivered, 0, delivered.length));
            assertArrayEquals(original, delivered, "seeded example " + example);
            assertEquals(0, buffer.outOfOrderBytes());
        }
    }

    private static List<Part> segment(Random random, byte[] bytes) {
        List<Part> parts = new ArrayList<>();
        for (int offset = 0; offset < bytes.length;) {
            int length = Math.min(bytes.length - offset, 1 + random.nextInt(32));
            parts.add(new Part(offset, Arrays.copyOfRange(bytes, offset, offset + length)));
            offset += length;
        }
        return parts;
    }

    private record Part(int offset, byte[] bytes) {}
}
