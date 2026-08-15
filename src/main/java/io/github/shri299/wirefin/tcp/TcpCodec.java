package io.github.shri299.wirefin.tcp;

import io.github.shri299.wirefin.ip.IpAddress;
import io.github.shri299.wirefin.ip.TransportChecksum;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

public final class TcpCodec {
    private TcpCodec() {}

    public static TcpSegment parse(byte[] wire, IpAddress source, IpAddress destination) {
        if (wire.length < 20) throw new MalformedSegmentException("TCP segment shorter than minimum header");
        int dataOffset = (wire[12] >>> 4) & 0xf;
        int headerLength = dataOffset * 4;
        if (dataOffset < 5 || headerLength > wire.length) throw new MalformedSegmentException("invalid TCP data offset");
        if (!checksumValid(wire, source, destination)) throw new MalformedSegmentException("invalid TCP checksum");
        ByteBuffer in = ByteBuffer.wrap(wire).order(ByteOrder.BIG_ENDIAN);
        int sourcePort = Short.toUnsignedInt(in.getShort());
        int destinationPort = Short.toUnsignedInt(in.getShort());
        long sequence = Integer.toUnsignedLong(in.getInt());
        long acknowledgement = Integer.toUnsignedLong(in.getInt());
        int offsetAndFlags = Short.toUnsignedInt(in.getShort());
        int flags = offsetAndFlags & 0x1ff;
        int window = Short.toUnsignedInt(in.getShort());
        in.getShort();
        int urgent = Short.toUnsignedInt(in.getShort());
        return new TcpSegment(sourcePort, destinationPort, sequence, acknowledgement, flags, window, urgent,
                Arrays.copyOfRange(wire, 20, headerLength), Arrays.copyOfRange(wire, headerLength, wire.length));
    }

    public static byte[] serialize(TcpSegment segment, IpAddress source, IpAddress destination) {
        int length = segment.headerLength() + segment.payload().length;
        if (length > 0xffff) throw new IllegalArgumentException("TCP segment too long for IPv4");
        byte[] wire = new byte[length];
        ByteBuffer out = ByteBuffer.wrap(wire).order(ByteOrder.BIG_ENDIAN);
        out.putShort((short) segment.sourcePort()).putShort((short) segment.destinationPort());
        out.putInt((int) segment.sequenceNumber()).putInt((int) segment.acknowledgementNumber());
        out.putShort((short) ((segment.headerLength() / 4 << 12) | segment.flags()));
        out.putShort((short) segment.windowSize()).putShort((short) 0).putShort((short) segment.urgentPointer());
        out.put(segment.options()).put(segment.payload());
        out.putShort(16, (short) checksum(wire, source, destination));
        return wire;
    }

    public static boolean checksumValid(byte[] wire, IpAddress source, IpAddress destination) {
        return checksum(wire, source, destination) == 0;
    }

    private static int checksum(byte[] wire, IpAddress source, IpAddress destination) {
        return TransportChecksum.compute(wire, source, destination, 6);
    }

    public static final class MalformedSegmentException extends IllegalArgumentException {
        public MalformedSegmentException(String message) { super(message); }
    }
}
