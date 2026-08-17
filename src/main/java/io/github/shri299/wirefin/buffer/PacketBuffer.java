package io.github.shri299.wirefin.buffer;

import java.nio.ByteBuffer;

/** Exclusively-owned bounded packet storage. Closing returns it to its pool once. */
public final class PacketBuffer implements AutoCloseable {
    private final PacketBufferPool pool; private final ByteBuffer memory;
    private boolean owned;
    PacketBuffer(PacketBufferPool pool, ByteBuffer memory) { this.pool = pool; this.memory = memory; }
    void acquire() { if (owned) throw new IllegalStateException("buffer already owned"); owned = true; memory.clear(); }
    public ByteBuffer memory() { if (!owned) throw new IllegalStateException("buffer not owned"); return memory; }
    public int capacity() { return memory.capacity(); }
    @Override public void close() { if (owned) { owned = false; memory.clear(); pool.recycle(this); } }
}
