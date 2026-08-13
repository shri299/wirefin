package io.github.shri299.wirefin.tcp.reliability;

/** Serial-number arithmetic for TCP's 32-bit sequence space (RFC 9293 §3.4.1). */
public final class SequenceNumber {
    public static final long MASK = 0xffff_ffffL;
    private SequenceNumber() {}
    public static long add(long sequence, long delta) { return (sequence + delta) & MASK; }
    public static long distance(long from, long to) { return (to - from) & MASK; }
    public static boolean lessThan(long a, long b) { return a != b && (int) (a - b) < 0; }
    public static boolean lessThanOrEqual(long a, long b) { return a == b || lessThan(a, b); }
    public static boolean greaterThan(long a, long b) { return lessThan(b, a); }
    public static boolean betweenInclusive(long value, long low, long high) {
        return !lessThan(value, low) && !greaterThan(value, high);
    }
}
