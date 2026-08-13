package io.github.shri299.wirefin.tcp.reliability;

import io.github.shri299.wirefin.tcp.TcpFlags;
import io.github.shri299.wirefin.tcp.TcpSegment;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Oldest-first retransmission queue with RFC 6298 RTT/RTO estimation and Karn sampling. */
public final class RetransmissionManager {
    private final RtoEstimator estimator;
    private final LinkedHashMap<Long, Outstanding> outstanding = new LinkedHashMap<>();

    public RetransmissionManager(Duration initialTimeout) {
        this(new RtoEstimator(initialTimeout, Duration.ofMillis(200), Duration.ofSeconds(60)));
    }
    public RetransmissionManager(RtoEstimator estimator) { this.estimator = estimator; }

    public synchronized void track(TcpSegment segment, long nowNanos) {
        if (segment.sequenceSpaceLength() == 0) return;
        outstanding.put(segment.sequenceNumber(), new Outstanding(segment, nowNanos,
                nowNanos + estimator.rtoNanos(), false));
    }

    public synchronized AckResult acknowledge(long acknowledgement, long nowNanos) {
        int bytes = 0;
        Long sample = null;
        boolean acknowledgedRetransmission = false;
        var iterator = outstanding.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Long, Outstanding> entry = iterator.next();
            Outstanding item = entry.getValue();
            long start = item.segment.sequenceNumber();
            long end = SequenceNumber.add(start, item.segment.sequenceSpaceLength());
            if (SequenceNumber.lessThanOrEqual(end, acknowledgement)) {
                bytes += item.segment.sequenceSpaceLength();
                if (!item.retransmitted) sample = nowNanos - item.sentAtNanos;
                else acknowledgedRetransmission = true;
                iterator.remove();
            } else if (SequenceNumber.greaterThan(acknowledgement, start)) {
                int consumed = (int) SequenceNumber.distance(start, acknowledgement);
                bytes += consumed;
                Outstanding trimmed = item.trim(consumed, acknowledgement);
                iterator.remove();
                replaceKeyPreservingOrder(entry.getKey(), acknowledgement, trimmed);
                break;
            } else break;
        }
        boolean sampled = sample != null && !acknowledgedRetransmission;
        if (sampled) estimator.sample(sample);
        rearmOldest(nowNanos);
        return new AckResult(bytes, sampled, estimator.rtoNanos());
    }

    public synchronized List<TcpSegment> due(long nowNanos) {
        Outstanding oldest = oldest();
        if (oldest == null || nowNanos < oldest.deadlineNanos) return List.of();
        estimator.backoff();
        replaceOldest(oldest.retransmitted(nowNanos, estimator.rtoNanos()));
        return List.of(oldest.segment);
    }

    public synchronized TcpSegment fastRetransmit(long nowNanos) {
        Outstanding oldest = oldest();
        if (oldest == null) return null;
        replaceOldest(oldest.retransmitted(nowNanos, estimator.rtoNanos()));
        return oldest.segment;
    }

    private void rearmOldest(long nowNanos) {
        Outstanding oldest = oldest();
        if (oldest != null) replaceOldest(new Outstanding(oldest.segment, oldest.sentAtNanos,
                nowNanos + estimator.rtoNanos(), oldest.retransmitted));
    }
    private Outstanding oldest() { return outstanding.isEmpty() ? null : outstanding.values().iterator().next(); }
    private void replaceOldest(Outstanding replacement) {
        Long key = outstanding.isEmpty() ? null : outstanding.keySet().iterator().next();
        if (key != null) outstanding.put(key, replacement);
    }
    private void replaceKeyPreservingOrder(Long oldKey, long newKey, Outstanding replacement) {
        LinkedHashMap<Long, Outstanding> rebuilt = new LinkedHashMap<>();
        boolean inserted = false;
        for (Map.Entry<Long, Outstanding> entry : outstanding.entrySet()) {
            if (!inserted && SequenceNumber.greaterThan(entry.getKey(), oldKey)) {
                rebuilt.put(newKey, replacement);
                inserted = true;
            }
            rebuilt.put(entry.getKey(), entry.getValue());
        }
        if (!inserted) rebuilt.put(newKey, replacement);
        outstanding.clear();
        outstanding.putAll(rebuilt);
    }

    public synchronized long bytesInFlight() { return outstanding.values().stream().mapToLong(v -> v.segment.sequenceSpaceLength()).sum(); }
    public synchronized int size() { return outstanding.size(); }
    public synchronized long rtoNanos() { return estimator.rtoNanos(); }

    public record AckResult(int newlyAcknowledgedBytes, boolean sampledRtt, long rtoNanos) {}

    private record Outstanding(TcpSegment segment, long sentAtNanos, long deadlineNanos, boolean retransmitted) {
        Outstanding retransmitted(long now, long rto) { return new Outstanding(segment, now, now + rto, true); }
        Outstanding trim(int consumed, long newSequence) {
            int payloadOffset = consumed - (segment.has(TcpFlags.SYN) ? 1 : 0);
            payloadOffset = Math.max(0, Math.min(payloadOffset, segment.payload().length));
            byte[] payload = Arrays.copyOfRange(segment.payload(), payloadOffset, segment.payload().length);
            int flags = segment.flags() & ~TcpFlags.SYN;
            if (consumed >= segment.sequenceSpaceLength()) flags &= ~TcpFlags.FIN;
            TcpSegment remainder = new TcpSegment(segment.sourcePort(), segment.destinationPort(), newSequence,
                    segment.acknowledgementNumber(), flags, segment.windowSize(), segment.urgentPointer(), segment.options(), payload);
            return new Outstanding(remainder, sentAtNanos, deadlineNanos, retransmitted);
        }
    }
}
