package io.github.shri299.wirefin.device;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.ArrayList;
import static org.junit.jupiter.api.Assertions.*;

class PacketBatchTest {
    @Test void boundedBatchAndCompatibilityDefaults() throws IOException {
        var device = new MemoryDevice(); var batch = new PacketBatch(2);
        assertEquals(1, device.receive(batch)); assertArrayEquals(new byte[]{1}, batch.get(0));
        assertTrue(batch.add(new byte[]{2})); assertFalse(batch.add(new byte[]{3}));
        assertEquals(2, device.transmit(batch)); assertEquals(2, device.writes.size());
        batch.clear(); assertEquals(0, batch.size());
    }
    private static final class MemoryDevice implements PacketDevice {
        final ArrayList<byte[]> writes = new ArrayList<>();
        public byte[] read() { return new byte[]{1}; }
        public void write(byte[] packet) { writes.add(packet); }
        public void close() {}
    }
}
