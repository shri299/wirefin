package io.github.shri299.wirefin.icmp;

import java.util.Arrays;

public record IcmpMessage(int type, int code, int restOfHeader, byte[] payload) {
    public IcmpMessage {
        if (type < 0 || type > 255 || code < 0 || code > 255 || payload == null)
            throw new IllegalArgumentException("invalid ICMP message");
        payload = Arrays.copyOf(payload, payload.length);
    }
    @Override public byte[] payload() { return Arrays.copyOf(payload, payload.length); }
    public int identifier() { return restOfHeader >>> 16; }
    public int sequence() { return restOfHeader & 0xffff; }
    @Override public boolean equals(Object other) { return other instanceof IcmpMessage that && type == that.type &&
            code == that.code && restOfHeader == that.restOfHeader && Arrays.equals(payload, that.payload); }
    @Override public int hashCode() { return 31 * java.util.Objects.hash(type, code, restOfHeader) + Arrays.hashCode(payload); }
}
