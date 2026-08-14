package io.github.shri299.wirefin.ip;

import io.github.shri299.wirefin.ipv4.InternetChecksum;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** IPv4/IPv6 pseudo-header checksum used by TCP, UDP, and ICMPv6. */
public final class TransportChecksum {
    private TransportChecksum() {}

    public static int compute(byte[] payload, IpAddress source, IpAddress destination, int protocol) {
        if (source.bitLength() != destination.bitLength()) throw new IllegalArgumentException("mixed IP families");
        ByteBuffer pseudo;
        if (source.bitLength() == 32) {
            if (payload.length > 0xffff) throw new IllegalArgumentException("transport payload too long");
            pseudo = ByteBuffer.allocate(12 + payload.length).order(ByteOrder.BIG_ENDIAN);
            pseudo.put(source.bytes()).put(destination.bytes()).put((byte) 0).put((byte) protocol)
                    .putShort((short) payload.length);
        } else {
            pseudo = ByteBuffer.allocate(40 + payload.length).order(ByteOrder.BIG_ENDIAN);
            pseudo.put(source.bytes()).put(destination.bytes()).putInt(payload.length)
                    .put(new byte[3]).put((byte) protocol);
        }
        pseudo.put(payload);
        return InternetChecksum.compute(pseudo.array());
    }
}
