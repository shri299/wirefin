package io.github.shri299.wirefin.ip;

import io.github.shri299.wirefin.ipv4.InternetChecksum;
import io.github.shri299.wirefin.view.OwnedPacketView;
import java.nio.ByteBuffer;

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

    public static int compute(ByteBuffer payload, int offset, int length, IpAddress source, IpAddress destination, int protocol) {
        if (source.bitLength() != destination.bitLength()) throw new IllegalArgumentException("mixed IP families");
        long sum = addressSum(source) + addressSum(destination);
        if (source.bitLength() == 32) { if (length > 0xffff) throw new IllegalArgumentException("transport payload too long"); sum += protocol + length; }
        else sum += (length >>> 16) + (length & 0xffff) + protocol;
        return InternetChecksum.compute(payload, offset, length, sum);
    }

    public static int compute(OwnedPacketView payload, int length, IpAddress source, IpAddress destination, int protocol) {
        if (source.bitLength() != destination.bitLength()) throw new IllegalArgumentException("mixed IP families");
        if (length < 0 || length > payload.length()) throw new IndexOutOfBoundsException("invalid payload length");
        long sum = addressSum(source) + addressSum(destination);
        if (source.bitLength() == 32) {
            if (length > 0xffff) throw new IllegalArgumentException("transport payload too long");
            sum += protocol + length;
        } else sum += (length >>> 16) + (length & 0xffff) + protocol;
        return payload.internetChecksum(0, length, sum);
    }

    private static long addressSum(IpAddress address) {
        long sum = 0;
        for (int i = 0; i < address.bitLength() / 8; i += 2)
            sum += address.unsignedByte(i) << 8 | address.unsignedByte(i + 1);
        return sum;
    }
}
