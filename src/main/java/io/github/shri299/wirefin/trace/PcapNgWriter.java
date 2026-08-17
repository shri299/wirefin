package io.github.shri299.wirefin.trace;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** PCAPNG writer with separate raw-IP RX and TX interfaces and nanosecond timestamps. */
public final class PcapNgWriter implements PacketCapture {
    private static final int SECTION_HEADER = 0x0a0d0d0a, INTERFACE_DESCRIPTION = 1, ENHANCED_PACKET = 6;
    private static final int LINKTYPE_RAW = 101;
    private final OutputStream output;
    private boolean closed;

    public PcapNgWriter(Path path) throws IOException {
        if (path == null) throw new IllegalArgumentException("capture path required");
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        output = new BufferedOutputStream(Files.newOutputStream(path));
        writeSection();
        writeInterface("wirefin-rx");
        writeInterface("wirefin-tx");
    }

    PcapNgWriter(OutputStream output) throws IOException {
        this.output = output;
        writeSection();
        writeInterface("wirefin-rx");
        writeInterface("wirefin-tx");
    }

    @Override public boolean enabled() { return true; }

    @Override public synchronized void record(long timestampNanos, Direction direction, byte[] packet) throws IOException {
        ensureOpen();
        if (timestampNanos < 0 || direction == null || packet == null) throw new IllegalArgumentException("invalid capture record");
        int paddedPacket = align4(packet.length);
        int optionsLength = 12; // epb_flags plus end-of-options
        int totalLength = 32 + paddedPacket + optionsLength;
        ByteBuffer block = block(totalLength);
        block.putInt(ENHANCED_PACKET).putInt(totalLength);
        block.putInt(direction == Direction.RX ? 0 : 1);
        block.putInt((int) (timestampNanos >>> 32)).putInt((int) timestampNanos);
        block.putInt(packet.length).putInt(packet.length).put(packet);
        while (block.position() < 28 + paddedPacket) block.put((byte) 0);
        block.putShort((short) 2).putShort((short) 4).putInt(direction == Direction.RX ? 1 : 2);
        block.putInt(0).putInt(totalLength);
        output.write(block.array());
    }

    @Override public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        output.close();
    }

    private void writeSection() throws IOException {
        ByteBuffer block = block(28);
        block.putInt(SECTION_HEADER).putInt(28).putInt(0x1a2b3c4d);
        block.putShort((short) 1).putShort((short) 0).putLong(-1).putInt(28);
        output.write(block.array());
    }

    private void writeInterface(String name) throws IOException {
        byte[] value = name.getBytes(StandardCharsets.UTF_8);
        int optionLength = 4 + align4(value.length) + 4 + 8; // if_name, if_tsresol, end
        int totalLength = 20 + optionLength;
        ByteBuffer block = block(totalLength);
        block.putInt(INTERFACE_DESCRIPTION).putInt(totalLength);
        block.putShort((short) LINKTYPE_RAW).putShort((short) 0).putInt(65_535);
        block.putShort((short) 2).putShort((short) value.length).put(value);
        while ((block.position() & 3) != 0) block.put((byte) 0);
        block.putShort((short) 9).putShort((short) 1).put((byte) 9).put(new byte[3]);
        block.putInt(0).putInt(totalLength);
        output.write(block.array());
    }

    private static ByteBuffer block(int length) { return ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN); }
    private static int align4(int value) { return (value + 3) & ~3; }
    private void ensureOpen() throws IOException { if (closed) throw new IOException("capture closed"); }
}
