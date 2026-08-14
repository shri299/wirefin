package io.github.shri299.wirefin.udp;

import io.github.shri299.wirefin.ip.IpAddress;
import io.github.shri299.wirefin.ip.TransportChecksum;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

public final class UdpCodec {
    private UdpCodec() {}
    public static UdpDatagram parse(byte[] wire, IpAddress source, IpAddress destination) {
        if (wire.length < 8) throw new MalformedDatagramException("UDP datagram shorter than header");
        ByteBuffer in = ByteBuffer.wrap(wire).order(ByteOrder.BIG_ENDIAN);
        int sourcePort = Short.toUnsignedInt(in.getShort()), destinationPort = Short.toUnsignedInt(in.getShort());
        int length = Short.toUnsignedInt(in.getShort()), checksum = Short.toUnsignedInt(in.getShort());
        if (length < 8 || length > wire.length) throw new MalformedDatagramException("invalid UDP length");
        byte[] exact = Arrays.copyOf(wire, length);
        if (source.bitLength() == 128 && checksum == 0) throw new MalformedDatagramException("IPv6 UDP checksum is mandatory");
        if (checksum != 0 && TransportChecksum.compute(exact, source, destination, 17) != 0)
            throw new MalformedDatagramException("invalid UDP checksum");
        return new UdpDatagram(sourcePort, destinationPort, Arrays.copyOfRange(exact, 8, length));
    }
    public static byte[] serialize(UdpDatagram datagram, IpAddress source, IpAddress destination) {
        int length = 8 + datagram.payload().length;
        ByteBuffer out = ByteBuffer.allocate(length).order(ByteOrder.BIG_ENDIAN);
        out.putShort((short) datagram.sourcePort()).putShort((short) datagram.destinationPort())
                .putShort((short) length).putShort((short) 0).put(datagram.payload());
        int checksum = TransportChecksum.compute(out.array(), source, destination, 17);
        out.putShort(6, (short) (checksum == 0 ? 0xffff : checksum));
        return out.array();
    }
    public static final class MalformedDatagramException extends IllegalArgumentException {
        public MalformedDatagramException(String message) { super(message); }
    }
}
