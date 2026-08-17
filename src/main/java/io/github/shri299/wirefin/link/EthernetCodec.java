package io.github.shri299.wirefin.link;

import java.nio.*;
import java.util.Arrays;

public final class EthernetCodec {
    private EthernetCodec() {}
    public static EthernetFrame parse(byte[] wire) {
        if (wire.length < 14) throw new IllegalArgumentException("truncated Ethernet frame");
        ByteBuffer in = ByteBuffer.wrap(wire).order(ByteOrder.BIG_ENDIAN); byte[] dst = new byte[6], src = new byte[6];
        in.get(dst).get(src); int type = Short.toUnsignedInt(in.getShort());
        return new EthernetFrame(new MacAddress(dst), new MacAddress(src), type, Arrays.copyOfRange(wire, 14, wire.length));
    }
    public static byte[] serialize(EthernetFrame frame) {
        ByteBuffer out = ByteBuffer.allocate(14 + frame.payloadLength()).order(ByteOrder.BIG_ENDIAN);
        frame.destination().writeTo(out); frame.source().writeTo(out); out.putShort((short) frame.etherType()); frame.writePayloadTo(out); return out.array();
    }
}
