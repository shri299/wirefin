package io.github.shri299.wirefin.memory;

import java.nio.ByteBuffer;

final class BufferPacketMemory implements PacketMemory {
    private final Owner owner;
    private final int offset;
    private final int length;
    private boolean open = true;

    private BufferPacketMemory(Owner owner, int offset, int length) {
        this.owner = owner;
        this.offset = offset;
        this.length = length;
    }

    static PacketMemory create(ByteBuffer memory, Runnable releaser) {
        ByteBuffer normalized = memory.slice().asReadOnlyBuffer();
        return new BufferPacketMemory(new Owner(normalized, releaser), 0, normalized.remaining());
    }

    @Override public synchronized int length() { ensureOpen(); return length; }

    @Override public synchronized byte get(int relative) {
        range(relative, 1);
        return owner.get(offset + relative);
    }

    @Override public synchronized void copyTo(int sourceOffset, byte[] destination, int destinationOffset, int count) {
        if (destination == null || destinationOffset < 0 || count < 0 || destinationOffset > destination.length - count)
            throw new IndexOutOfBoundsException("invalid destination range");
        range(sourceOffset, count);
        owner.copyTo(offset + sourceOffset, destination, destinationOffset, count);
    }

    @Override public synchronized PacketMemory slice(int relative, int sliceLength) {
        range(relative, sliceLength);
        owner.retain();
        return new BufferPacketMemory(owner, offset + relative, sliceLength);
    }

    @Override public synchronized void close() {
        if (!open) throw new IllegalStateException("packet memory already released");
        open = false;
        owner.release();
    }

    private void range(int relative, int count) {
        ensureOpen();
        if (relative < 0 || count < 0 || relative > length - count)
            throw new IndexOutOfBoundsException("packet memory range outside view");
    }

    private void ensureOpen() {
        if (!open) throw new IllegalStateException("packet memory released");
    }

    private static final class Owner {
        private final ByteBuffer memory;
        private final Runnable releaser;
        private int references = 1;
        private boolean released;

        private Owner(ByteBuffer memory, Runnable releaser) {
            this.memory = memory;
            this.releaser = releaser;
        }

        synchronized void retain() {
            if (released) throw new IllegalStateException("packet storage released");
            references = Math.addExact(references, 1);
        }

        synchronized byte get(int index) {
            if (released) throw new IllegalStateException("packet storage released");
            return memory.get(index);
        }

        synchronized void copyTo(int offset, byte[] destination, int destinationOffset, int length) {
            if (released) throw new IllegalStateException("packet storage released");
            memory.get(offset, destination, destinationOffset, length);
        }

        void release() {
            Runnable callback = null;
            synchronized (this) {
                if (released || references < 1) throw new IllegalStateException("invalid packet storage release");
                if (--references == 0) {
                    released = true;
                    callback = releaser;
                }
            }
            if (callback != null) callback.run();
        }
    }
}
