package io.github.shri299.wirefin.link;

import io.github.shri299.wirefin.ipv4.Ipv4Address;
import java.nio.*;

public final class ArpCodec {
    private ArpCodec() {}
    public static ArpPacket parse(byte[] wire) {
        if (wire.length < 28) throw new IllegalArgumentException("truncated Ethernet/IPv4 ARP");
        ByteBuffer in = ByteBuffer.wrap(wire).order(ByteOrder.BIG_ENDIAN);
        if (Short.toUnsignedInt(in.getShort()) != 1 || Short.toUnsignedInt(in.getShort()) != EthernetFrame.IPV4 ||
                Byte.toUnsignedInt(in.get()) != 6 || Byte.toUnsignedInt(in.get()) != 4) throw new IllegalArgumentException("unsupported ARP format");
        int operation = Short.toUnsignedInt(in.getShort()); byte[] sm = new byte[6], tm = new byte[6];
        in.get(sm); var sip = new Ipv4Address(in.getInt()); in.get(tm); var tip = new Ipv4Address(in.getInt());
        return new ArpPacket(operation, new MacAddress(sm), sip, new MacAddress(tm), tip);
    }
    public static byte[] serialize(ArpPacket packet) {
        ByteBuffer out = ByteBuffer.allocate(28).order(ByteOrder.BIG_ENDIAN);
        out.putShort((short)1).putShort((short)EthernetFrame.IPV4).put((byte)6).put((byte)4).putShort((short)packet.operation());
        packet.senderMac().writeTo(out); out.putInt(packet.senderIp().value()); packet.targetMac().writeTo(out); out.putInt(packet.targetIp().value()); return out.array();
    }
}
