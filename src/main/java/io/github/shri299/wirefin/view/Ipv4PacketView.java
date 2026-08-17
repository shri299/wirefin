package io.github.shri299.wirefin.view;

import io.github.shri299.wirefin.ipv4.*;
import java.nio.ByteBuffer;

public final class Ipv4PacketView {
    private final PacketView packet; private final int headerLength, totalLength;
    private Ipv4PacketView(PacketView packet, int headerLength, int totalLength) { this.packet=packet;this.headerLength=headerLength;this.totalLength=totalLength; }
    public static Ipv4PacketView parse(ByteBuffer memory, int offset, int available) {
        PacketView view = new PacketView(memory, offset, available); if (available < 20 || view.unsignedByte(0) >>> 4 != 4) throw new IllegalArgumentException("not IPv4");
        int header = (view.unsignedByte(0) & 15) * 4, total = view.unsignedShort(2);
        if (header < 20 || total < header || total > available) throw new IllegalArgumentException("invalid IPv4 lengths");
        if (InternetChecksum.compute(memory, offset, header, 0) != 0) throw new IllegalArgumentException("invalid IPv4 checksum");
        return new Ipv4PacketView(view, header, total);
    }
    public int protocol() { return packet.unsignedByte(9); }
    public Ipv4Address source() { return new Ipv4Address(intAt(12)); }
    public Ipv4Address destination() { return new Ipv4Address(intAt(16)); }
    public PacketView payload() { return packet.subview(headerLength, totalLength - headerLength); }
    private int intAt(int offset) { return packet.unsignedShort(offset) << 16 | packet.unsignedShort(offset + 2); }
}
