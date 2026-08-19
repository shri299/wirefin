package io.github.shri299.wirefin.metrics;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/** Dependency-free counters; snapshots are outside the packet hot path. */
public final class NetworkMetrics {
    private final boolean detailedProtocolMetrics;
    private final LongAdder rxPackets = new LongAdder(), txPackets = new LongAdder(), rxBytes = new LongAdder(),
            txBytes = new LongAdder(), drops = new LongAdder(), retransmissions = new LongAdder(),
            fastRetransmits = new LongAdder(), rtoEvents = new LongAdder(), poolMisses = new LongAdder(),
            batches = new LongAdder(), batchPackets = new LongAdder(), connectionsOpened = new LongAdder(),
            connectionsClosed = new LongAdder(), connectionsReset = new LongAdder(), sackEvents = new LongAdder(),
            zeroWindowEvents = new LongAdder(), resourceRejections = new LongAdder();
    private final AtomicLong activeConnections = new AtomicLong(), poolInUse = new AtomicLong(),
            poolCapacity = new AtomicLong(), queueDepth = new AtomicLong(), udpDatagrams = new AtomicLong(),
            icmpMessages = new AtomicLong(), malformedPackets = new AtomicLong(), checksumFailures = new AtomicLong(),
            fragmentsReceived = new AtomicLong(), fragmentsAssembled = new AtomicLong(), fragmentTimeouts = new AtomicLong();
    private final long startedNanos = System.nanoTime();
    public NetworkMetrics() { this(false); }
    public NetworkMetrics(boolean detailedProtocolMetrics) { this.detailedProtocolMetrics=detailedProtocolMetrics; }
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
    public void connectionOpened() { connectionsOpened.increment(); }
    public void connectionClosed() { connectionsClosed.increment(); }
    public void connectionReset() { connectionsReset.increment(); }
    public void udpDatagram() { if(detailedProtocolMetrics)incrementSingleWriter(udpDatagrams); }
    public void icmpMessage() { if(detailedProtocolMetrics)incrementSingleWriter(icmpMessages); }
    public void malformedPacket() { if(detailedProtocolMetrics)incrementSingleWriter(malformedPackets); }
    public void checksumFailure() { if(detailedProtocolMetrics)incrementSingleWriter(checksumFailures); }
    public void fragmentReceived() { if(detailedProtocolMetrics)incrementSingleWriter(fragmentsReceived); }
    public void fragmentAssembled() { if(detailedProtocolMetrics)incrementSingleWriter(fragmentsAssembled); }
    public void fragmentTimeouts(long count) { if(detailedProtocolMetrics)fragmentTimeouts.setOpaque(fragmentTimeouts.getOpaque()+count); }
    public void sackEvents(long count) { sackEvents.add(count); }
    public void zeroWindowEvents(long count) { zeroWindowEvents.add(count); }
    public void resourceRejected() { resourceRejections.increment(); }
    public boolean detailedProtocolMetrics() { return detailedProtocolMetrics; }
    public Snapshot snapshot() {
        long batchCount = batches.sum();
        long rxPacketCount=rxPackets.sum(),txPacketCount=txPackets.sum(),rxByteCount=rxBytes.sum(),txByteCount=txBytes.sum();
        double seconds=Math.max(1,System.nanoTime()-startedNanos)/1_000_000_000.0;
        return new Snapshot(rxPacketCount, txPacketCount, rxByteCount, txByteCount, drops.sum(),
                retransmissions.sum(), fastRetransmits.sum(), rtoEvents.sum(), activeConnections.get(),
                poolInUse.get(), poolCapacity.get(), poolMisses.sum(), queueDepth.get(), batchCount,
                batchCount == 0 ? 0 : (double) batchPackets.sum() / batchCount,
                connectionsOpened.sum(),connectionsClosed.sum(),connectionsReset.sum(),udpDatagrams.get(),
                icmpMessages.get(),malformedPackets.get(),checksumFailures.get(),fragmentsReceived.get(),
                fragmentsAssembled.get(),fragmentTimeouts.get(),sackEvents.sum(),zeroWindowEvents.sum(),resourceRejections.sum(),
                (rxPacketCount+txPacketCount)/seconds,(rxByteCount+txByteCount)*8.0/1_000_000_000.0/seconds);
    }
    private static void incrementSingleWriter(AtomicLong counter) { counter.setOpaque(counter.getOpaque()+1); }
    public record Snapshot(long rxPackets, long txPackets, long rxBytes, long txBytes, long drops,
                           long retransmissions, long fastRetransmits, long rtoEvents, long activeConnections,
                           long poolInUse, long poolCapacity, long poolMisses, long queueDepth,
                           long batches, double averageBatchSize, long connectionsOpened, long connectionsClosed,
                           long connectionsReset, long udpDatagrams, long icmpMessages, long malformedPackets,
                           long checksumFailures, long fragmentsReceived, long fragmentsAssembled, long fragmentTimeouts,long sackEvents,
                           long zeroWindowEvents, long resourceRejections, double packetsPerSecond,
                           double gigabitsPerSecond) {}
}
