package io.github.shri299.wirefin.ip;

import io.github.shri299.wirefin.ipv4.InternetChecksum;

/** IPv4/IPv6 pseudo-header checksum used by TCP, UDP, and ICMPv6. */
public final class TransportChecksum {
    private TransportChecksum() {}

    public static int compute(byte[] payload, IpAddress source, IpAddress destination, int protocol) {
        if (source.bitLength() != destination.bitLength()) throw new IllegalArgumentException("mixed IP families");
        long sum = addressSum(source) + addressSum(destination);
        if (source.bitLength() == 32) {
            if (payload.length > 0xffff) throw new IllegalArgumentException("transport payload too long");
            sum += protocol + payload.length;
        } else {
            sum += (payload.length >>> 16) + (payload.length & 0xffff) + protocol;
        }
        return InternetChecksum.compute(payload, 0, payload.length, sum);
    }

    private static long addressSum(IpAddress address) {
        long sum = 0;
        for (int i = 0; i < address.bitLength() / 8; i += 2)
            sum += address.unsignedByte(i) << 8 | address.unsignedByte(i + 1);
        return sum;
    }
}
