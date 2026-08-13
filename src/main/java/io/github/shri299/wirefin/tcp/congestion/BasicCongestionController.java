package io.github.shri299.wirefin.tcp.congestion;

/** Educational Reno-like slow start/congestion avoidance without fast recovery. */
public final class BasicCongestionController implements CongestionController {
    private final int maximumSegmentSize;
    private long cwnd;
    private long ssthresh = 65_535;

    public BasicCongestionController(int maximumSegmentSize) {
        if (maximumSegmentSize <= 0) throw new IllegalArgumentException("MSS must be positive");
        this.maximumSegmentSize = maximumSegmentSize;
        this.cwnd = maximumSegmentSize;
    }

    @Override public synchronized void onAcknowledgement(int bytes) {
        if (bytes <= 0) return;
        if (cwnd < ssthresh) cwnd += Math.min(bytes, maximumSegmentSize);
        else cwnd += Math.max(1, (long) maximumSegmentSize * maximumSegmentSize / cwnd);
    }

    @Override public synchronized void onLoss() {
        ssthresh = Math.max(cwnd / 2, 2L * maximumSegmentSize);
        cwnd = maximumSegmentSize;
    }

    @Override public synchronized long congestionWindow() { return cwnd; }
    @Override public synchronized long slowStartThreshold() { return ssthresh; }
}
