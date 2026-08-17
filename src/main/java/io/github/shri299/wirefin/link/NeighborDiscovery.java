package io.github.shri299.wirefin.link;

import io.github.shri299.wirefin.icmp.*;
import io.github.shri299.wirefin.ipv6.Ipv6Address;
import java.nio.ByteBuffer;
import java.util.Arrays;

/** Minimal RFC 4861 NS/NA payload support for an Ethernet backend. */
public final class NeighborDiscovery {
    private NeighborDiscovery() {}
    public static IcmpMessage solicitation(Ipv6Address target, MacAddress sourceMac) {
        ByteBuffer payload = ByteBuffer.allocate(24); payload.put(target.bytes()).put((byte)1).put((byte)1).put(sourceMac.bytes());
        return new IcmpMessage(135, 0, 0, payload.array());
    }
    public static IcmpMessage advertisement(Ipv6Address target, MacAddress targetMac, boolean solicited, boolean override) {
        int flags = (solicited ? 1 << 30 : 0) | (override ? 1 << 29 : 0);
        ByteBuffer payload = ByteBuffer.allocate(24); payload.put(target.bytes()).put((byte)2).put((byte)1).put(targetMac.bytes());
        return new IcmpMessage(136, 0, flags, payload.array());
    }
    public static NdMessage parse(IcmpMessage message) {
        if ((message.type() != 135 && message.type() != 136) || message.code() != 0 || message.payload().length < 16)
            throw new IllegalArgumentException("not a supported neighbor discovery message");
        byte[] payload = message.payload(); Ipv6Address target = new Ipv6Address(Arrays.copyOf(payload, 16));
        MacAddress mac = null;
        for (int offset = 16; offset + 2 <= payload.length;) {
            int type = payload[offset] & 0xff, units = payload[offset + 1] & 0xff, length = units * 8;
            if (length == 0 || offset + length > payload.length) throw new IllegalArgumentException("malformed ND option");
            if ((type == 1 || type == 2) && length == 8) mac = new MacAddress(Arrays.copyOfRange(payload, offset + 2, offset + 8));
            offset += length;
        }
        return new NdMessage(message.type(), message.restOfHeader(), target, mac);
    }
    public record NdMessage(int type, int flags, Ipv6Address target, MacAddress linkLayerAddress) {}
}
