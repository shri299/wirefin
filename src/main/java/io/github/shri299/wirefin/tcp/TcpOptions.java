package io.github.shri299.wirefin.tcp;

import java.util.OptionalInt;

/** Minimal, bounds-checked TCP option support. */
public final class TcpOptions {
    private static final int END = 0, NOP = 1, MSS = 2;
    private TcpOptions() {}

    public static OptionalInt maximumSegmentSize(byte[] options) {
        int offset = 0;
        while (offset < options.length) {
            int kind = Byte.toUnsignedInt(options[offset]);
            if (kind == END) break;
            if (kind == NOP) { offset++; continue; }
            if (offset + 1 >= options.length) return OptionalInt.empty();
            int length = Byte.toUnsignedInt(options[offset + 1]);
            if (length < 2 || offset + length > options.length) return OptionalInt.empty();
            if (kind == MSS && length == 4) {
                int value = (Byte.toUnsignedInt(options[offset + 2]) << 8) |
                        Byte.toUnsignedInt(options[offset + 3]);
                return value == 0 ? OptionalInt.empty() : OptionalInt.of(value);
            }
            offset += length;
        }
        return OptionalInt.empty();
    }

    public static byte[] mss(int value) {
        if (value < 1 || value > 65_535) throw new IllegalArgumentException("invalid MSS");
        return new byte[] {MSS, 4, (byte) (value >>> 8), (byte) value};
    }
}
