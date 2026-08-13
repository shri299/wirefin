package io.github.shri299.wirefin.ipv4;

import java.util.Arrays;

public record Ipv4Packet(int dscpEcn, int identification, int flags, int fragmentOffset,
                         int ttl, int protocol, Ipv4Address source, Ipv4Address destination,
                         byte[] options, byte[] payload) {
    public static final int PROTOCOL_TCP = 6;

    public Ipv4Packet {
        if (dscpEcn < 0 || dscpEcn > 255 || identification < 0 || identification > 0xffff ||
                flags < 0 || flags > 7 || fragmentOffset < 0 || fragmentOffset > 0x1fff ||
                ttl < 0 || ttl > 255 || protocol < 0 || protocol > 255)
            throw new IllegalArgumentException("IPv4 field outside wire range");
        if (options == null || payload == null || source == null || destination == null)
            throw new IllegalArgumentException("IPv4 fields must not be null");
        if ((options.length & 3) != 0 || options.length > 40) throw new IllegalArgumentException("invalid options length");
        options = Arrays.copyOf(options, options.length);
        payload = Arrays.copyOf(payload, payload.length);
    }

    @Override public byte[] options() { return Arrays.copyOf(options, options.length); }
    @Override public byte[] payload() { return Arrays.copyOf(payload, payload.length); }
    public int headerLength() { return 20 + options.length; }
    public int totalLength() { return headerLength() + payload.length; }
    public boolean isFragmented() { return fragmentOffset != 0 || (flags & 1) != 0; }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        return other instanceof Ipv4Packet that && dscpEcn == that.dscpEcn &&
                identification == that.identification && flags == that.flags &&
                fragmentOffset == that.fragmentOffset && ttl == that.ttl && protocol == that.protocol &&
                source.equals(that.source) && destination.equals(that.destination) &&
                Arrays.equals(options, that.options) && Arrays.equals(payload, that.payload);
    }

    @Override public int hashCode() {
        int result = java.util.Objects.hash(dscpEcn, identification, flags, fragmentOffset, ttl, protocol, source, destination);
        result = 31 * result + Arrays.hashCode(options);
        return 31 * result + Arrays.hashCode(payload);
    }
}
