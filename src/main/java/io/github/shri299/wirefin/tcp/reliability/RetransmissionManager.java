package io.github.shri299.wirefin.tcp.reliability;

import io.github.shri299.wirefin.tcp.TcpSegment;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Cumulative ACK tracking and a simple exponential-backoff retransmission timer. */
public final class RetransmissionManager {
    private final long initialTimeoutNanos;
    private final Map<Long, Outstanding> outstanding = new LinkedHashMap<>();

    public RetransmissionManager(Duration initialTimeout) {
        if (initialTimeout.isZero() || initialTimeout.isNegative()) throw new IllegalArgumentException("RTO must be positive");
        initialTimeoutNanos = initialTimeout.toNanos();
    }

    public synchronized void track(TcpSegment segment, long nowNanos) {
        if (segment.sequenceSpaceLength() == 0) return;
        outstanding.put(segment.sequenceNumber(), new Outstanding(segment, nowNanos + initialTimeoutNanos, initialTimeoutNanos));
    }

    public synchronized int acknowledge(long acknowledgement) {
        int bytes = 0;
        Iterator<Outstanding> iterator = outstanding.values().iterator();
        while (iterator.hasNext()) {
            Outstanding item = iterator.next();
            long end = SequenceNumber.add(item.segment.sequenceNumber(), item.segment.sequenceSpaceLength());
            if (SequenceNumber.lessThanOrEqual(end, acknowledgement)) {
                bytes += item.segment.sequenceSpaceLength();
                iterator.remove();
            }
        }
        return bytes;
    }

    public synchronized List<TcpSegment> due(long nowNanos) {
        List<TcpSegment> due = new ArrayList<>();
        outstanding.replaceAll((sequence, item) -> {
            if (nowNanos < item.deadlineNanos) return item;
            due.add(item.segment);
            long timeout = Math.min(item.timeoutNanos * 2, Duration.ofSeconds(60).toNanos());
            return new Outstanding(item.segment, nowNanos + timeout, timeout);
        });
        return due;
    }

    public synchronized long bytesInFlight() {
        return outstanding.values().stream().mapToLong(value -> value.segment.sequenceSpaceLength()).sum();
    }
    public synchronized int size() { return outstanding.size(); }

    private record Outstanding(TcpSegment segment, long deadlineNanos, long timeoutNanos) {}
}
