package io.github.shri299.wirefin.icmp;

import io.github.shri299.wirefin.ip.IpAddress;
import io.github.shri299.wirefin.ip.TransportChecksum;
import io.github.shri299.wirefin.ipv4.InternetChecksum;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

public final class IcmpCodec {
    private IcmpCodec() {}
    public static IcmpMessage parseV4(byte[] wire) { return parse(wire, null, null); }
    public static IcmpMessage parseV6(byte[] wire, IpAddress source, IpAddress destination) { return parse(wire, source, destination); }
    private static IcmpMessage parse(byte[] wire, IpAddress source, IpAddress destination) {
        if (wire.length < 8) throw new MalformedMessageException("ICMP message shorter than header");
        int checksum = source == null ? InternetChecksum.compute(wire) : TransportChecksum.compute(wire, source, destination, 58);
        if (checksum != 0) throw new MalformedMessageException("invalid ICMP checksum");
        ByteBuffer in = ByteBuffer.wrap(wire).order(ByteOrder.BIG_ENDIAN);
        int type = Byte.toUnsignedInt(in.get()), code = Byte.toUnsignedInt(in.get()); in.getShort();
        int rest = in.getInt();
        return new IcmpMessage(type, code, rest, Arrays.copyOfRange(wire, 8, wire.length));
    }
    public static byte[] serializeV4(IcmpMessage message) { return serialize(message, null, null); }
    public static byte[] serializeV6(IcmpMessage message, IpAddress source, IpAddress destination) {
        return serialize(message, source, destination);
    }
    private static byte[] serialize(IcmpMessage message, IpAddress source, IpAddress destination) {
        ByteBuffer out = ByteBuffer.allocate(8 + message.payload().length).order(ByteOrder.BIG_ENDIAN);
        out.put((byte) message.type()).put((byte) message.code()).putShort((short) 0)
                .putInt(message.restOfHeader()).put(message.payload());
        int checksum = source == null ? InternetChecksum.compute(out.array()) :
                TransportChecksum.compute(out.array(), source, destination, 58);
        out.putShort(2, (short) checksum); return out.array();
    }
    public static final class MalformedMessageException extends IllegalArgumentException {
        public MalformedMessageException(String message) { super(message); }
    }
}
