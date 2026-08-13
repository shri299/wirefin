package io.github.shri299.wirefin.ipv4;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

public final class Ipv4Codec {
    private Ipv4Codec() {}

    public static Ipv4Packet parse(byte[] wire) {
        if (wire.length < 20) throw new MalformedPacketException("IPv4 packet shorter than minimum header");
        int version = (wire[0] >>> 4) & 0xf;
        int ihl = wire[0] & 0xf;
        if (version != 4) throw new MalformedPacketException("not IPv4 (version=" + version + ")");
        if (ihl < 5) throw new MalformedPacketException("IPv4 IHL below 5");
        int headerLength = ihl * 4;
        if (wire.length < headerLength) throw new MalformedPacketException("truncated IPv4 header");
        int totalLength = unsignedShort(wire, 2);
        if (totalLength < headerLength || totalLength > wire.length)
            throw new MalformedPacketException("invalid IPv4 total length " + totalLength);
        if (InternetChecksum.compute(wire, 0, headerLength, 0) != 0)
            throw new MalformedPacketException("invalid IPv4 header checksum");
        ByteBuffer in = ByteBuffer.wrap(wire).order(ByteOrder.BIG_ENDIAN);
        in.position(1);
        int dscpEcn = Byte.toUnsignedInt(in.get());
        in.getShort();
        int identification = Short.toUnsignedInt(in.getShort());
        int flagsFragment = Short.toUnsignedInt(in.getShort());
        int ttl = Byte.toUnsignedInt(in.get());
        int protocol = Byte.toUnsignedInt(in.get());
        in.getShort();
        Ipv4Address source = new Ipv4Address(in.getInt());
        Ipv4Address destination = new Ipv4Address(in.getInt());
        return new Ipv4Packet(dscpEcn, identification, flagsFragment >>> 13,
                flagsFragment & 0x1fff, ttl, protocol, source, destination,
                Arrays.copyOfRange(wire, 20, headerLength), Arrays.copyOfRange(wire, headerLength, totalLength));
    }

    public static byte[] serialize(Ipv4Packet packet) {
        if (packet.totalLength() > 0xffff) throw new IllegalArgumentException("IPv4 packet exceeds 65535 bytes");
        byte[] wire = new byte[packet.totalLength()];
        ByteBuffer out = ByteBuffer.wrap(wire).order(ByteOrder.BIG_ENDIAN);
        out.put((byte) ((4 << 4) | (packet.headerLength() / 4)));
        out.put((byte) packet.dscpEcn()).putShort((short) packet.totalLength());
        out.putShort((short) packet.identification());
        out.putShort((short) ((packet.flags() << 13) | packet.fragmentOffset()));
        out.put((byte) packet.ttl()).put((byte) packet.protocol()).putShort((short) 0);
        out.putInt(packet.source().value()).putInt(packet.destination().value());
        out.put(packet.options()).put(packet.payload());
        out.putShort(10, (short) InternetChecksum.compute(wire, 0, packet.headerLength(), 0));
        return wire;
    }

    private static int unsignedShort(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xff) << 8) | (bytes[offset + 1] & 0xff);
    }

    public static final class MalformedPacketException extends IllegalArgumentException {
        public MalformedPacketException(String message) { super(message); }
    }
}
