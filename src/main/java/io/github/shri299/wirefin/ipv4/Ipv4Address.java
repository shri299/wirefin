package io.github.shri299.wirefin.ipv4;

import io.github.shri299.wirefin.ip.IpAddress;
import java.util.Objects;

public record Ipv4Address(int value) implements IpAddress {
    public static Ipv4Address parse(String text) {
        Objects.requireNonNull(text);
        String[] parts = text.split("\\.", -1);
        if (parts.length != 4) throw new IllegalArgumentException("invalid IPv4 address: " + text);
        int value = 0;
        for (String part : parts) {
            int octet;
            try { octet = Integer.parseInt(part); }
            catch (NumberFormatException e) { throw new IllegalArgumentException("invalid IPv4 address: " + text, e); }
            if (octet < 0 || octet > 255) throw new IllegalArgumentException("invalid IPv4 address: " + text);
            value = (value << 8) | octet;
        }
        return new Ipv4Address(value);
    }

    public byte[] bytes() {
        return new byte[] {(byte)(value >>> 24), (byte)(value >>> 16), (byte)(value >>> 8), (byte)value};
    }

    @Override public int bitLength() { return 32; }

    @Override public String toString() {
        return (value >>> 24 & 0xff) + "." + (value >>> 16 & 0xff) + "." +
                (value >>> 8 & 0xff) + "." + (value & 0xff);
    }
}
