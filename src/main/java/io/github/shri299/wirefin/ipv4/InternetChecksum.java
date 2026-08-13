package io.github.shri299.wirefin.ipv4;

/** RFC 1071 one's-complement Internet checksum. */
public final class InternetChecksum {
    private InternetChecksum() {}

    public static int compute(byte[] bytes) { return compute(bytes, 0, bytes.length, 0); }

    public static int compute(byte[] bytes, int offset, int length, long initialSum) {
        long sum = initialSum;
        int end = offset + length;
        for (int i = offset; i + 1 < end; i += 2) {
            sum += ((bytes[i] & 0xff) << 8) | (bytes[i + 1] & 0xff);
            sum = (sum & 0xffff) + (sum >>> 16);
        }
        if ((length & 1) != 0) sum += (bytes[end - 1] & 0xff) << 8;
        while ((sum >>> 16) != 0) sum = (sum & 0xffff) + (sum >>> 16);
        return (int) (~sum) & 0xffff;
    }
}
