package io.github.shri299.wirefin.link;

import java.util.Arrays;

public final class MacAddress {
    public static final MacAddress BROADCAST = new MacAddress(new byte[]{-1,-1,-1,-1,-1,-1});
    private final byte[] bytes;
    public MacAddress(byte[] bytes) {
        if (bytes == null || bytes.length != 6) throw new IllegalArgumentException("MAC address must be 6 bytes");
        this.bytes = Arrays.copyOf(bytes, 6);
    }
    public static MacAddress parse(String text) {
        String[] fields = text.split(":", -1); if (fields.length != 6) throw new IllegalArgumentException("invalid MAC address");
        byte[] bytes = new byte[6];
        try { for (int i = 0; i < 6; i++) { if (fields[i].length() != 2) throw new NumberFormatException(); bytes[i] = (byte) Integer.parseInt(fields[i], 16); } }
        catch (NumberFormatException failure) { throw new IllegalArgumentException("invalid MAC address: " + text, failure); }
        return new MacAddress(bytes);
    }
    public byte[] bytes() { return Arrays.copyOf(bytes, 6); }
    void writeTo(java.nio.ByteBuffer target) { target.put(bytes); }
    @Override public boolean equals(Object other) { return other instanceof MacAddress that && Arrays.equals(bytes, that.bytes); }
    @Override public int hashCode() { return Arrays.hashCode(bytes); }
    @Override public String toString() { return String.format("%02x:%02x:%02x:%02x:%02x:%02x", bytes[0]&255,bytes[1]&255,bytes[2]&255,bytes[3]&255,bytes[4]&255,bytes[5]&255); }
}
