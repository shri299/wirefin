package io.github.shri299.wirefin.tcp.congestion;

/** Configurable educational congestion-control strategies. */
public enum CongestionControlAlgorithm {
    RENO {
        @Override public CongestionController create(int mss) { return new BasicCongestionController(mss); }
    },
    CUBIC {
        @Override public CongestionController create(int mss) { return new CubicCongestionController(mss); }
    };

    public abstract CongestionController create(int maximumSegmentSize);

    public static CongestionControlAlgorithm parse(String value) {
        try { return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT)); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException("congestion control must be reno or cubic", invalid); }
    }
}
