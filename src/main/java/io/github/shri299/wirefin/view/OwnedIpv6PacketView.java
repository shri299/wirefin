package io.github.shri299.wirefin.view;

import io.github.shri299.wirefin.ipv6.Ipv6Address;
import io.github.shri299.wirefin.memory.PacketMemory;

/** Owned IPv6 fixed-header/payload view over packet memory. */
public final class OwnedIpv6PacketView implements AutoCloseable {
    private final OwnedPacketView packet;
    private final int payloadLength;

    private OwnedIpv6PacketView(OwnedPacketView packet, int payloadLength) {
        this.packet = packet;
        this.payloadLength = payloadLength;
    }

    /** Transfers ownership of {@code memory} to the returned IPv6 view. */
    public static OwnedIpv6PacketView parse(PacketMemory memory) {
        OwnedPacketView view = OwnedPacketView.takeOwnership(memory);
        try {
            if (view.length() < 40 || view.unsignedByte(0) >>> 4 != 6) throw new IllegalArgumentException("not IPv6");
            int length = view.unsignedShort(4);
            if (40 + length > view.length()) throw new IllegalArgumentException("truncated IPv6 payload");
            return new OwnedIpv6PacketView(view, length);
        } catch (RuntimeException failure) {
            view.close();
            throw failure;
        }
    }

    public int nextHeader() { return packet.unsignedByte(6); }
    public Ipv6Address source() { return address(8); }
    public Ipv6Address destination() { return address(24); }
    public OwnedPacketView payload() { return packet.slice(40, payloadLength); }
    private Ipv6Address address(int offset) {
        byte[] bytes = new byte[16];
        packet.copyTo(offset, bytes, 0, bytes.length);
        return new Ipv6Address(bytes);
    }
    @Override public void close() { packet.close(); }
}
