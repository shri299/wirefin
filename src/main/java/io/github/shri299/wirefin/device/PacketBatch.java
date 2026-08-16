package io.github.shri299.wirefin.device;

import java.util.Arrays;

/** Bounded container reused by device event loops; it does not copy packet arrays. */
public final class PacketBatch {
    private final byte[][] packets;
    private int size;
    public PacketBatch(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("batch capacity must be positive");
        packets = new byte[capacity][];
    }
    public int capacity() { return packets.length; }
    public int size() { return size; }
    public boolean isFull() { return size == packets.length; }
    public byte[] get(int index) { if (index < 0 || index >= size) throw new IndexOutOfBoundsException(index); return packets[index]; }
    public boolean add(byte[] packet) {
        if (packet == null) throw new IllegalArgumentException("packet must not be null");
        if (isFull()) return false; packets[size++] = packet; return true;
    }
    public void clear() { Arrays.fill(packets, 0, size, null); size = 0; }
}
