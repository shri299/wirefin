package io.github.shri299.wirefin.ipv6;

import io.github.shri299.wirefin.ip.IpAddress;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;

public final class Ipv6Address implements IpAddress {
    private final byte[] bytes;
    public Ipv6Address(byte[] bytes) {
        if (bytes == null || bytes.length != 16) throw new IllegalArgumentException("IPv6 address must be 16 bytes");
        this.bytes = Arrays.copyOf(bytes, 16);
    }
    public static Ipv6Address parse(String text) {
        if (text == null || !text.contains(":") || text.contains("%"))
            throw new IllegalArgumentException("invalid IPv6 address: " + text);
        try {
            InetAddress parsed = InetAddress.getByName(text);
            if (!(parsed instanceof Inet6Address)) throw new IllegalArgumentException("not an IPv6 address: " + text);
            return new Ipv6Address(parsed.getAddress());
        } catch (UnknownHostException e) { throw new IllegalArgumentException("invalid IPv6 address: " + text, e); }
    }
    @Override public byte[] bytes() { return Arrays.copyOf(bytes, 16); }
    @Override public int bitLength() { return 128; }
    @Override public int unsignedByte(int index) { return Byte.toUnsignedInt(bytes[index]); }
    @Override public boolean equals(Object other) { return other instanceof Ipv6Address that && Arrays.equals(bytes, that.bytes); }
    @Override public int hashCode() { return Arrays.hashCode(bytes); }
    @Override public String toString() {
        try { return Inet6Address.getByAddress(null, bytes, -1).getHostAddress(); }
        catch (UnknownHostException impossible) { throw new AssertionError(impossible); }
    }
}
