package io.github.shri299.wirefin.ipv6;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

public final class Ipv6Codec {
    private Ipv6Codec() {}
    public static Ipv6Packet parse(byte[] wire) {
        if (wire.length < 40) throw new MalformedPacketException("IPv6 packet shorter than fixed header");
        ByteBuffer in = ByteBuffer.wrap(wire).order(ByteOrder.BIG_ENDIAN);
        int first = in.getInt();
        if ((first >>> 28) != 6) throw new MalformedPacketException("not IPv6");
        int payloadLength = Short.toUnsignedInt(in.getShort());
        if (wire.length < 40 + payloadLength) throw new MalformedPacketException("truncated IPv6 payload");
        int next = Byte.toUnsignedInt(in.get()), hop = Byte.toUnsignedInt(in.get());
        byte[] source = new byte[16], destination = new byte[16]; in.get(source).get(destination);
        return new Ipv6Packet((first >>> 20) & 0xff, first & 0xfffff, next, hop,
                new Ipv6Address(source), new Ipv6Address(destination), Arrays.copyOfRange(wire, 40, 40 + payloadLength));
    }
    public static byte[] serialize(Ipv6Packet packet) {
        ByteBuffer out = ByteBuffer.allocate(40 + packet.payload().length).order(ByteOrder.BIG_ENDIAN);
        out.putInt(6 << 28 | packet.trafficClass() << 20 | packet.flowLabel());
        out.putShort((short) packet.payload().length).put((byte) packet.nextHeader()).put((byte) packet.hopLimit());
        out.put(packet.source().bytes()).put(packet.destination().bytes()).put(packet.payload());
        return out.array();
    }
    public static final class MalformedPacketException extends IllegalArgumentException {
        public MalformedPacketException(String message) { super(message); }
    }
}
