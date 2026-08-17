package io.github.shri299.wirefin.view;

import io.github.shri299.wirefin.memory.PacketMemory;

/** Checked protocol view that owns one reference to its packet memory. */
public final class OwnedPacketView implements AutoCloseable {
    private final PacketMemory memory;

    private OwnedPacketView(PacketMemory memory) { this.memory = memory; }

    /** Transfers ownership of {@code memory} to the returned view. */
    public static OwnedPacketView takeOwnership(PacketMemory memory) {
        if (memory == null) throw new IllegalArgumentException("packet memory required");
        return new OwnedPacketView(memory);
    }

    public int length() { return memory.length(); }
    public int unsignedByte(int offset) { return memory.getUnsignedByte(offset); }
    public int unsignedShort(int offset) { return memory.getUnsignedShort(offset); }
    public long unsignedInt(int offset) { return memory.getUnsignedInt(offset); }
    public OwnedPacketView slice(int offset, int length) {
        return new OwnedPacketView(memory.slice(offset, length));
    }
    public void copyTo(int sourceOffset, byte[] destination, int destinationOffset, int length) {
        memory.copyTo(sourceOffset, destination, destinationOffset, length);
    }
    public int internetChecksum(int offset, int length, long initialSum) {
        if (offset < 0 || length < 0 || offset > memory.length() - length)
            throw new IndexOutOfBoundsException("checksum range outside packet view");
        long sum = initialSum;
        int end = offset + length;
        for (int i = offset; i + 1 < end; i += 2) {
            sum += unsignedByte(i) << 8 | unsignedByte(i + 1);
            sum = (sum & 0xffff) + (sum >>> 16);
        }
        if ((length & 1) != 0) sum += unsignedByte(end - 1) << 8;
        while ((sum >>> 16) != 0) sum = (sum & 0xffff) + (sum >>> 16);
        return (int) (~sum) & 0xffff;
    }
    @Override public void close() { memory.close(); }
}
