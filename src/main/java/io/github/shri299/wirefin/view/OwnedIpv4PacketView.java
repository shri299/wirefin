package io.github.shri299.wirefin.view;

import io.github.shri299.wirefin.ipv4.Ipv4Address;
import io.github.shri299.wirefin.memory.PacketMemory;

/** Owned IPv4 header/payload view over heap, direct, pooled, or native storage. */
public final class OwnedIpv4PacketView implements AutoCloseable {
    private final OwnedPacketView packet;
    private final int headerLength;
    private final int totalLength;

    private OwnedIpv4PacketView(OwnedPacketView packet, int headerLength, int totalLength) {
        this.packet = packet;
        this.headerLength = headerLength;
        this.totalLength = totalLength;
    }

    /** Transfers ownership of {@code memory} to the returned IPv4 view. */
    public static OwnedIpv4PacketView parse(PacketMemory memory) {
        OwnedPacketView view = OwnedPacketView.takeOwnership(memory);
        try {
            if (view.length() < 20 || view.unsignedByte(0) >>> 4 != 4) throw new IllegalArgumentException("not IPv4");
            int header = (view.unsignedByte(0) & 15) * 4;
            int total = view.unsignedShort(2);
            if (header < 20 || total < header || total > view.length()) throw new IllegalArgumentException("invalid IPv4 lengths");
            if (view.internetChecksum(0, header, 0) != 0) throw new IllegalArgumentException("invalid IPv4 checksum");
            return new OwnedIpv4PacketView(view, header, total);
        } catch (RuntimeException failure) {
            view.close();
            throw failure;
        }
    }

    public int protocol() { return packet.unsignedByte(9); }
    public Ipv4Address source() { return new Ipv4Address((int) packet.unsignedInt(12)); }
    public Ipv4Address destination() { return new Ipv4Address((int) packet.unsignedInt(16)); }
    public OwnedPacketView payload() { return packet.slice(headerLength, totalLength - headerLength); }
    @Override public void close() { packet.close(); }
}
