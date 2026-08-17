package io.github.shri299.wirefin.memory;

import java.nio.ByteBuffer;

/**
 * A checked, exclusively released view of packet storage.
 *
 * <p>Every instance owns one reference. {@link #slice(int, int)} creates another
 * owned reference; both views must be closed exactly once. Access after close and
 * duplicate close are rejected. Implementations release their backing storage
 * after the last reference closes.</p>
 */
public interface PacketMemory extends AutoCloseable {
    int length();
    byte get(int offset);

    default int getUnsignedByte(int offset) { return Byte.toUnsignedInt(get(offset)); }
    default int getUnsignedShort(int offset) {
        return getUnsignedByte(offset) << 8 | getUnsignedByte(Math.addExact(offset, 1));
    }
    default long getUnsignedInt(int offset) {
        return (long) getUnsignedShort(offset) << 16 | getUnsignedShort(Math.addExact(offset, 2));
    }

    void copyTo(int sourceOffset, byte[] destination, int destinationOffset, int length);
    PacketMemory slice(int offset, int length);

    default byte[] copyToArray() {
        byte[] result = new byte[length()];
        copyTo(0, result, 0, result.length);
        return result;
    }

    /** Takes ownership of the array without copying it. The caller must not mutate it. */
    static PacketMemory takeOwnership(byte[] bytes) {
        if (bytes == null) throw new IllegalArgumentException("packet bytes required");
        return BufferPacketMemory.create(ByteBuffer.wrap(bytes), () -> { });
    }

    /** Copies caller-owned bytes into independent heap-backed packet storage. */
    static PacketMemory copyOf(byte[] bytes) {
        return takeOwnership(bytes.clone());
    }

    /** Allocates independently owned direct packet storage. */
    static PacketMemory allocateDirect(int length) {
        if (length < 0) throw new IllegalArgumentException("negative packet length");
        return BufferPacketMemory.create(ByteBuffer.allocateDirect(length), () -> { });
    }

    /**
     * Takes ownership of a buffer range and invokes {@code releaser} once after
     * the final slice is closed. Intended for pools and native packet storage.
     */
    static PacketMemory takeOwnership(ByteBuffer memory, int offset, int length, Runnable releaser) {
        if (memory == null || releaser == null || offset < 0 || length < 0 || offset > memory.limit() - length)
            throw new IllegalArgumentException("invalid packet memory");
        ByteBuffer range = memory.duplicate();
        range.position(offset).limit(offset + length);
        return BufferPacketMemory.create(range.slice(), releaser);
    }

    @Override void close();
}
