package io.github.shri299.wirefin.view;

import io.github.shri299.wirefin.link.MacAddress;
import io.github.shri299.wirefin.memory.PacketMemory;

/** Owned, no-payload-copy view of an Ethernet II frame. */
public final class EthernetFrameView implements AutoCloseable {
    private static final int HEADER_LENGTH = 14;
    private final OwnedPacketView frame;

    private EthernetFrameView(OwnedPacketView frame) { this.frame = frame; }

    /** Transfers ownership of {@code memory} to the returned frame view. */
    public static EthernetFrameView parse(PacketMemory memory) {
        OwnedPacketView view = OwnedPacketView.takeOwnership(memory);
        if (view.length() < HEADER_LENGTH) {
            view.close();
            throw new IllegalArgumentException("Ethernet frame shorter than header");
        }
        return new EthernetFrameView(view);
    }

    public MacAddress destination() { return mac(0); }
    public MacAddress source() { return mac(6); }
    public int etherType() { return frame.unsignedShort(12); }
    public OwnedPacketView payload() { return frame.slice(HEADER_LENGTH, frame.length() - HEADER_LENGTH); }

    private MacAddress mac(int offset) {
        byte[] bytes = new byte[6];
        frame.copyTo(offset, bytes, 0, bytes.length);
        return new MacAddress(bytes);
    }

    @Override public void close() { frame.close(); }
}
