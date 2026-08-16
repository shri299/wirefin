package io.github.shri299.wirefin.buffer;

import io.github.shri299.wirefin.metrics.NetworkMetrics;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;

/** Fixed-size pool: acquisition failure is explicit and memory cannot grow. */
public final class PacketBufferPool {
    private final ArrayDeque<PacketBuffer> available; private final int capacity;
    private final NetworkMetrics metrics;
    public PacketBufferPool(int capacity, int bufferSize, boolean direct, NetworkMetrics metrics) {
        if (capacity < 1 || bufferSize < 1 || metrics == null) throw new IllegalArgumentException("invalid pool configuration");
        this.capacity = capacity; this.metrics = metrics; available = new ArrayDeque<>(capacity);
        for (int i = 0; i < capacity; i++) available.add(new PacketBuffer(this,
                direct ? ByteBuffer.allocateDirect(bufferSize) : ByteBuffer.allocate(bufferSize)));
        metrics.poolState(0, capacity);
    }
    public synchronized PacketBuffer acquire() {
        PacketBuffer buffer = available.pollFirst();
        if (buffer == null) { metrics.poolMiss(); return null; }
        buffer.acquire(); metrics.poolState(capacity - available.size(), capacity); return buffer;
    }
    synchronized void recycle(PacketBuffer buffer) {
        if (available.size() >= capacity) throw new IllegalStateException("foreign or duplicate buffer recycle");
        available.addFirst(buffer); metrics.poolState(capacity - available.size(), capacity);
    }
    public int capacity() { return capacity; }
    public synchronized int available() { return available.size(); }
}
