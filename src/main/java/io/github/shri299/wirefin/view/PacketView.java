package io.github.shri299.wirefin.view;

import java.nio.ByteBuffer;

/** Read-only range over caller-owned memory; no payload bytes are copied. */
public record PacketView(ByteBuffer memory, int offset, int length) {
    public PacketView {
        if (memory == null || offset < 0 || length < 0 || offset + length > memory.limit()) throw new IllegalArgumentException("invalid packet view");
        memory = memory.asReadOnlyBuffer();
    }
    public int unsignedByte(int relative) { range(relative, 1); return Byte.toUnsignedInt(memory.get(offset + relative)); }
    public int unsignedShort(int relative) { range(relative, 2); return unsignedByte(relative) << 8 | unsignedByte(relative + 1); }
    public PacketView subview(int relative, int subLength) { range(relative, subLength); return new PacketView(memory, offset + relative, subLength); }
    private void range(int relative, int size) { if (relative < 0 || size < 0 || relative + size > length) throw new IndexOutOfBoundsException(); }
}
