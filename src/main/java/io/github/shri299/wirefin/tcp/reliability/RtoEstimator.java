package io.github.shri299.wirefin.tcp.reliability;

import java.time.Duration;

/** RFC 6298 SRTT/RTTVAR estimator with configurable bounds for deterministic tests. */
public final class RtoEstimator {
    private final long initialRto;
    private final long minimumRto;
    private final long maximumRto;
    private long srtt = -1;
    private long rttvar;
    private long rto;

    public RtoEstimator(Duration initial, Duration minimum, Duration maximum) {
        initialRto = positive(initial, "initial RTO");
        minimumRto = positive(minimum, "minimum RTO");
        maximumRto = positive(maximum, "maximum RTO");
        if (minimumRto > initialRto || initialRto > maximumRto) throw new IllegalArgumentException("RTO bounds are inconsistent");
        rto = initialRto;
    }

    public synchronized void sample(long measuredRttNanos) {
        if (measuredRttNanos <= 0) return;
        if (srtt < 0) {
            srtt = measuredRttNanos;
            rttvar = measuredRttNanos / 2;
        } else {
            long error = Math.abs(srtt - measuredRttNanos);
            rttvar = (3 * rttvar + error) / 4;
            srtt = (7 * srtt + measuredRttNanos) / 8;
        }
        rto = clamp(srtt + Math.max(1_000_000L, 4 * rttvar));
    }

    public synchronized void backoff() { rto = clamp(rto > maximumRto / 2 ? maximumRto : rto * 2); }
    public synchronized long rtoNanos() { return rto; }
    public synchronized long smoothedRttNanos() { return srtt; }
    public synchronized long rttVariationNanos() { return rttvar; }
    private long clamp(long value) { return Math.max(minimumRto, Math.min(maximumRto, value)); }
    private static long positive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) throw new IllegalArgumentException(name + " must be positive");
        return value.toNanos();
    }
}
