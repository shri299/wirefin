package io.github.shri299.wirefin.view;

import io.github.shri299.wirefin.ip.IpAddress;
import io.github.shri299.wirefin.ip.TransportChecksum;

/** Owned, no-payload-copy TCP header and payload view. */
public final class TcpSegmentView implements AutoCloseable {
    private final OwnedPacketView segment;
    private final int headerLength;

    private TcpSegmentView(OwnedPacketView segment, int headerLength) {
        this.segment = segment;
        this.headerLength = headerLength;
    }

    /** Transfers ownership of {@code segment} to the returned TCP view. */
    public static TcpSegmentView parse(OwnedPacketView segment, IpAddress source, IpAddress destination) {
        try {
            if (segment.length() < 20) throw new IllegalArgumentException("TCP segment shorter than minimum header");
            int headerLength = (segment.unsignedByte(12) >>> 4) * 4;
            if (headerLength < 20 || headerLength > segment.length()) throw new IllegalArgumentException("invalid TCP data offset");
            if (TransportChecksum.compute(segment, segment.length(), source, destination, 6) != 0)
                throw new IllegalArgumentException("invalid TCP checksum");
            return new TcpSegmentView(segment, headerLength);
        } catch (RuntimeException failure) {
            segment.close();
            throw failure;
        }
    }

    public int sourcePort() { return segment.unsignedShort(0); }
    public int destinationPort() { return segment.unsignedShort(2); }
    public long sequenceNumber() { return unsignedInt(4); }
    public long acknowledgementNumber() { return unsignedInt(8); }
    public int flags() { return segment.unsignedByte(13); }
    public int windowSize() { return segment.unsignedShort(14); }
    public OwnedPacketView options() { return segment.slice(20, headerLength - 20); }
    public OwnedPacketView payload() { return segment.slice(headerLength, segment.length() - headerLength); }

    private long unsignedInt(int offset) {
        return segment.unsignedInt(offset);
    }

    @Override public void close() { segment.close(); }
}
