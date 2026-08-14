package io.github.shri299.wirefin.ipv6;

import java.util.Arrays;

/** IPv6 fixed header plus upper-layer payload; extension headers are intentionally unsupported. */
public record Ipv6Packet(int trafficClass, int flowLabel, int nextHeader, int hopLimit,
                         Ipv6Address source, Ipv6Address destination, byte[] payload) {
    public static final int NEXT_TCP = 6, NEXT_UDP = 17, NEXT_ICMPV6 = 58;
    public Ipv6Packet {
        if (trafficClass < 0 || trafficClass > 255 || flowLabel < 0 || flowLabel > 0xfffff ||
                nextHeader < 0 || nextHeader > 255 || hopLimit < 0 || hopLimit > 255)
            throw new IllegalArgumentException("IPv6 field outside wire range");
        if (source == null || destination == null || payload == null || payload.length > 0xffff)
            throw new IllegalArgumentException("invalid IPv6 packet");
        payload = Arrays.copyOf(payload, payload.length);
    }
    @Override public byte[] payload() { return Arrays.copyOf(payload, payload.length); }
    @Override public boolean equals(Object other) { return other instanceof Ipv6Packet that && trafficClass == that.trafficClass &&
            flowLabel == that.flowLabel && nextHeader == that.nextHeader && hopLimit == that.hopLimit &&
            source.equals(that.source) && destination.equals(that.destination) && Arrays.equals(payload, that.payload); }
    @Override public int hashCode() { return 31 * java.util.Objects.hash(trafficClass, flowLabel, nextHeader, hopLimit, source, destination) + Arrays.hashCode(payload); }
}
