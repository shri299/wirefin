package io.github.shri299.wirefin.buffer;

import io.github.shri299.wirefin.metrics.NetworkMetrics;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PacketBufferPoolTest {
    @Test void poolIsBoundedAndRecyclesExactlyOnce() {
        var metrics = new NetworkMetrics(); var pool = new PacketBufferPool(2, 2048, true, metrics);
        PacketBuffer first = pool.acquire(), second = pool.acquire();
        assertTrue(first.memory().isDirect()); assertNull(pool.acquire());
        assertEquals(1, metrics.snapshot().poolMisses()); assertEquals(2, metrics.snapshot().poolInUse());
        first.close(); first.close(); assertEquals(1, pool.available());
        PacketBuffer reused = pool.acquire(); assertSame(first, reused); assertEquals(2048, reused.capacity());
        reused.close(); second.close(); assertEquals(2, pool.available()); assertEquals(0, metrics.snapshot().poolInUse());
    }
}
