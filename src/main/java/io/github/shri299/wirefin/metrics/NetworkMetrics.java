package io.github.shri299.wirefin.metrics;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/** Dependency-free counters; snapshots are outside the packet hot path. */
public final class NetworkMetrics {
    private final LongAdder rxPackets = new LongAdder(), txPackets = new LongAdder(), rxBytes = new LongAdder(),
            txBytes = new LongAdder(), drops = new LongAdder(), retransmissions = new LongAdder(),
            fastRetransmits = new LongAdder(), rtoEvents = new LongAdder(), poolMisses = new LongAdder(),
            batches = new LongAdder(), batchPackets = new LongAdder();
    private final AtomicLong activeConnections = new AtomicLong(), poolInUse = new AtomicLong(),
            poolCapacity = new AtomicLong(), queueDepth = new AtomicLong();
    public void received(int bytes) { rxPackets.increment(); rxBytes.add(bytes); }
    public void transmitted(int bytes) { txPackets.increment(); txBytes.add(bytes); }
    public void drop() { drops.increment(); }
    public void retransmissions(long count) { retransmissions.add(count); }
    public void fastRetransmit() { fastRetransmits.increment(); }
    public void fastRetransmits(long count) { fastRetransmits.add(count); }
    public void rtoEvent() { rtoEvents.increment(); }
    public void rtoEvents(long count) { rtoEvents.add(count); }
    public void activeConnections(long value) { activeConnections.set(value); }
    public void poolMiss() { poolMisses.increment(); }
    public void poolState(long inUse, long capacity) { poolInUse.set(inUse); poolCapacity.set(capacity); }
    public void queueDepth(long value) { queueDepth.set(value); }
    public void batch(int size) { batches.increment(); batchPackets.add(size); }
    public Snapshot snapshot() {
        long batchCount = batches.sum();
        return new Snapshot(rxPackets.sum(), txPackets.sum(), rxBytes.sum(), txBytes.sum(), drops.sum(),
                retransmissions.sum(), fastRetransmits.sum(), rtoEvents.sum(), activeConnections.get(),
                poolInUse.get(), poolCapacity.get(), poolMisses.sum(), queueDepth.get(), batchCount,
                batchCount == 0 ? 0 : (double) batchPackets.sum() / batchCount);
    }
    public record Snapshot(long rxPackets, long txPackets, long rxBytes, long txBytes, long drops,
                           long retransmissions, long fastRetransmits, long rtoEvents, long activeConnections,
                           long poolInUse, long poolCapacity, long poolMisses, long queueDepth,
                           long batches, double averageBatchSize) {}
}
