package io.github.shri299.wirefin.tcp.congestion;

/**
 * Educational CUBIC window growth with NewReno-style recovery mechanics.
 * This implements the cubic target shape and multiplicative decrease, but is
 * not represented as Linux-equivalent or a complete RFC 9438 implementation.
 */
public final class CubicCongestionController implements CongestionController {
    private static final double C = 0.4;
    private static final double BETA = 0.7;
    private static final long MAXIMUM_WINDOW = 0xffff_ffffL;
    private final int mss;
    private long cwnd;
    private long ssthresh = 65_535;
    private long maximumWindow;
    private long epochStartNanos = Long.MIN_VALUE;
    private boolean fastRecovery;
    private long recoveryPoint;

    public CubicCongestionController(int maximumSegmentSize) {
        if (maximumSegmentSize <= 0) throw new IllegalArgumentException("MSS must be positive");
        mss = maximumSegmentSize;
        cwnd = maximumSegmentSize;
    }

    @Override public synchronized void onAcknowledgement(int bytes) {
        onAcknowledgement(bytes, System.nanoTime());
    }

    @Override public synchronized void onAcknowledgement(int bytes, long nowNanos) {
        if (bytes <= 0) return;
        if (cwnd < ssthresh) {
            cwnd = boundedAdd(cwnd, Math.min(bytes, mss));
            return;
        }
        if (epochStartNanos == Long.MIN_VALUE) {
            epochStartNanos = nowNanos;
            if (maximumWindow == 0) maximumWindow = cwnd;
        }
        double elapsedSeconds = Math.max(0, nowNanos - epochStartNanos) / 1_000_000_000.0;
        double distanceSegments = Math.max(0, maximumWindow - cwnd) / (double) mss;
        double k = Math.cbrt(distanceSegments / C);
        double target = maximumWindow + C * Math.pow(elapsedSeconds - k, 3) * mss;
        long cubicIncrement = target > cwnd ? Math.max(1, (long) ((target - cwnd) * Math.min(bytes, mss) / cwnd)) : 0;
        long renoIncrement = Math.max(1, (long) mss * Math.min(bytes, mss) / cwnd);
        cwnd = boundedAdd(cwnd, Math.max(cubicIncrement, renoIncrement)); // TCP-friendly floor for this educational subset.
    }

    @Override public synchronized void onTimeout(long bytesInFlight) {
        reduce(Math.max(cwnd, bytesInFlight));
        cwnd = mss;
        fastRecovery = false;
    }

    @Override public synchronized void onFastRetransmit(long bytesInFlight) { onFastRetransmit(bytesInFlight, 0); }

    @Override public synchronized void onFastRetransmit(long bytesInFlight, long recoveryPoint) {
        reduce(Math.max(cwnd, bytesInFlight));
        cwnd = ssthresh + 3L * mss;
        fastRecovery = true;
        this.recoveryPoint = recoveryPoint;
    }

    private void reduce(long window) {
        maximumWindow = Math.max(window, 2L * mss);
        ssthresh = Math.max(2L * mss, (long) (maximumWindow * BETA));
        epochStartNanos = Long.MIN_VALUE;
    }

    private static long boundedAdd(long value, long increment) {
        return increment >= MAXIMUM_WINDOW - value ? MAXIMUM_WINDOW : value + increment;
    }

    @Override public synchronized void onDuplicateAck() { if (fastRecovery) cwnd = boundedAdd(cwnd, mss); }
    @Override public synchronized void onPartialAcknowledgement(int bytes) {
        if (fastRecovery) cwnd = Math.max(ssthresh + mss, cwnd - Math.max(0, bytes));
    }
    @Override public synchronized void onRecoveryComplete() { fastRecovery = false; cwnd = ssthresh; epochStartNanos = Long.MIN_VALUE; }
    @Override public synchronized boolean inFastRecovery() { return fastRecovery; }
    @Override public synchronized long recoveryPoint() { return recoveryPoint; }
    @Override public synchronized long congestionWindow() { return cwnd; }
    @Override public synchronized long slowStartThreshold() { return ssthresh; }
}
