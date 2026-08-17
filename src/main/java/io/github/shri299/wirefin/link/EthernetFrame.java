package io.github.shri299.wirefin.link;

import java.util.Arrays;

public record EthernetFrame(MacAddress destination, MacAddress source, int etherType, byte[] payload) {
    public static final int IPV4 = 0x0800, ARP = 0x0806, IPV6 = 0x86dd;
    public EthernetFrame { if (destination == null || source == null || etherType < 0 || etherType > 0xffff || payload == null) throw new IllegalArgumentException("invalid Ethernet frame"); payload = Arrays.copyOf(payload, payload.length); }
    @Override public byte[] payload() { return Arrays.copyOf(payload, payload.length); }
    int payloadLength() { return payload.length; }
    void writePayloadTo(java.nio.ByteBuffer target) { target.put(payload); }
    @Override public boolean equals(Object other) { return other instanceof EthernetFrame that && destination.equals(that.destination) && source.equals(that.source) && etherType == that.etherType && Arrays.equals(payload, that.payload); }
    @Override public int hashCode() { return 31 * java.util.Objects.hash(destination, source, etherType) + Arrays.hashCode(payload); }
}
