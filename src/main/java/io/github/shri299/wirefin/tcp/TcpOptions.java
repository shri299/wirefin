package io.github.shri299.wirefin.tcp;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/** Bounds-checked TCP option parser and encoder for Wirefin's negotiated subset. */
public final class TcpOptions {
    private static final int END = 0, NOP = 1, MSS = 2, WINDOW_SCALE = 3;
    private static final int SACK_PERMITTED = 4, SACK = 5, TIMESTAMP = 8;
    private TcpOptions() {}

    public static Parsed parse(byte[] options) {
        OptionalInt mss = OptionalInt.empty();
        OptionalInt scale = OptionalInt.empty();
        boolean sackPermitted = false;
        Optional<Timestamp> timestamp = Optional.empty();
        List<SackBlock> blocks = new ArrayList<>();
        int offset = 0;
        while (offset < options.length) {
            int kind = Byte.toUnsignedInt(options[offset]);
            if (kind == END) break;
            if (kind == NOP) { offset++; continue; }
            if (offset + 1 >= options.length) break;
            int length = Byte.toUnsignedInt(options[offset + 1]);
            if (length < 2 || offset + length > options.length) break;
            if (kind == MSS && length == 4) {
                int value = u16(options, offset + 2);
                if (value > 0) mss = OptionalInt.of(value);
            } else if (kind == WINDOW_SCALE && length == 3) {
                scale = OptionalInt.of(Math.min(14, Byte.toUnsignedInt(options[offset + 2])));
            } else if (kind == SACK_PERMITTED && length == 2) {
                sackPermitted = true;
            } else if (kind == TIMESTAMP && length == 10) {
                timestamp = Optional.of(new Timestamp(u32(options, offset + 2), u32(options, offset + 6)));
            } else if (kind == SACK && length >= 10 && (length - 2) % 8 == 0) {
                for (int cursor = offset + 2; cursor < offset + length; cursor += 8)
                    blocks.add(new SackBlock(u32(options, cursor), u32(options, cursor + 4)));
            }
            offset += length;
        }
        return new Parsed(mss, scale, sackPermitted, timestamp, blocks);
    }

    public static OptionalInt maximumSegmentSize(byte[] options) { return parse(options).maximumSegmentSize(); }
    public static OptionalInt windowScale(byte[] options) { return parse(options).windowScale(); }

    public static byte[] mss(int value) {
        if (value < 1 || value > 65_535) throw new IllegalArgumentException("invalid MSS");
        return new byte[] {MSS, 4, (byte) (value >>> 8), (byte) value};
    }

    public static byte[] syn(int mss, Integer windowScale, boolean sackPermitted, Long timestampValue) {
        return syn(mss, windowScale, sackPermitted, timestampValue, 0);
    }

    public static byte[] syn(int mss, Integer windowScale, boolean sackPermitted, Long timestampValue, long timestampEcho) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(mss(mss));
        if (windowScale != null) {
            if (windowScale < 0 || windowScale > 14) throw new IllegalArgumentException("invalid window scale");
            out.write(WINDOW_SCALE); out.write(3); out.write(windowScale); out.write(NOP);
        }
        if (sackPermitted) { out.write(SACK_PERMITTED); out.write(2); out.write(NOP); out.write(NOP); }
        if (timestampValue != null) writeTimestamp(out, timestampValue, timestampEcho);
        return padded(out.toByteArray());
    }

    public static byte[] established(Timestamp timestamp, List<SackBlock> sackBlocks) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (timestamp != null) writeTimestamp(out, timestamp.value(), timestamp.echoReply());
        if (sackBlocks != null && !sackBlocks.isEmpty()) {
            int count = Math.min(timestamp == null ? 4 : 3, sackBlocks.size());
            out.write(SACK); out.write(2 + count * 8);
            for (int i = 0; i < count; i++) {
                SackBlock block = sackBlocks.get(i);
                write32(out, block.leftEdge()); write32(out, block.rightEdge());
            }
        }
        return padded(out.toByteArray());
    }

    private static void writeTimestamp(ByteArrayOutputStream out, long value, long echo) {
        out.write(NOP); out.write(NOP); out.write(TIMESTAMP); out.write(10);
        write32(out, value); write32(out, echo);
    }
    private static void write32(ByteArrayOutputStream out, long value) {
        out.write((byte) (value >>> 24)); out.write((byte) (value >>> 16));
        out.write((byte) (value >>> 8)); out.write((byte) value);
    }
    private static byte[] padded(byte[] value) {
        int length = (value.length + 3) & ~3;
        if (length > 40) throw new IllegalArgumentException("TCP options exceed 40 bytes");
        return java.util.Arrays.copyOf(value, length);
    }
    private static int u16(byte[] value, int offset) {
        return Byte.toUnsignedInt(value[offset]) << 8 | Byte.toUnsignedInt(value[offset + 1]);
    }
    private static long u32(byte[] value, int offset) {
        return (long) Byte.toUnsignedInt(value[offset]) << 24 |
                (long) Byte.toUnsignedInt(value[offset + 1]) << 16 |
                (long) Byte.toUnsignedInt(value[offset + 2]) << 8 |
                Byte.toUnsignedInt(value[offset + 3]);
    }

    public record Parsed(OptionalInt maximumSegmentSize, OptionalInt windowScale, boolean sackPermitted,
                         Optional<Timestamp> timestamp, List<SackBlock> sackBlocks) {
        public Parsed { sackBlocks = List.copyOf(sackBlocks); }
    }
    public record Timestamp(long value, long echoReply) {
        public Timestamp { value &= 0xffff_ffffL; echoReply &= 0xffff_ffffL; }
    }
    public record SackBlock(long leftEdge, long rightEdge) {
        public SackBlock { leftEdge &= 0xffff_ffffL; rightEdge &= 0xffff_ffffL; }
    }
}
