package io.github.shri299.wirefin.view;

import io.github.shri299.wirefin.ip.IpAddress;
import io.github.shri299.wirefin.ip.TransportChecksum;

/** Owned UDP header/payload view over packet memory. */
public final class OwnedUdpDatagramView implements AutoCloseable {
    private final OwnedPacketView datagram;
    private final int length;

    private OwnedUdpDatagramView(OwnedPacketView datagram, int length) {
        this.datagram = datagram;
        this.length = length;
    }

    /** Transfers ownership of {@code datagram} to the returned UDP view. */
    public static OwnedUdpDatagramView parse(OwnedPacketView datagram, IpAddress source, IpAddress destination) {
        try {
            if (datagram.length() < 8) throw new IllegalArgumentException("truncated UDP");
            int length = datagram.unsignedShort(4);
            int checksum = datagram.unsignedShort(6);
            if (length < 8 || length > datagram.length() || source.bitLength() == 128 && checksum == 0)
                throw new IllegalArgumentException("invalid UDP header");
            if (checksum != 0 && TransportChecksum.compute(datagram, length, source, destination, 17) != 0)
                throw new IllegalArgumentException("invalid UDP checksum");
            return new OwnedUdpDatagramView(datagram, length);
        } catch (RuntimeException failure) {
            datagram.close();
            throw failure;
        }
    }

    public int sourcePort() { return datagram.unsignedShort(0); }
    public int destinationPort() { return datagram.unsignedShort(2); }
    public OwnedPacketView payload() { return datagram.slice(8, length - 8); }
    @Override public void close() { datagram.close(); }
}
