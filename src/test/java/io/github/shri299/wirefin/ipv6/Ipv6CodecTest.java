package io.github.shri299.wirefin.ipv6;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class Ipv6CodecTest {
    @Test void fixedHeaderRoundTripAndCompression() {
        var packet = new Ipv6Packet(0x2e, 0xabcde, 17, 64, Ipv6Address.parse("2001:db8::1"),
                Ipv6Address.parse("2001:db8::2"), new byte[]{1, 2, 3});
        assertEquals(packet, Ipv6Codec.parse(Ipv6Codec.serialize(packet)));
        assertEquals("2001:db8:0:0:0:0:0:1", packet.source().toString());
    }
    @Test void rejectsTruncatedPayload() {
        byte[] wire = Ipv6Codec.serialize(new Ipv6Packet(0, 0, 6, 64, Ipv6Address.parse("::1"),
                Ipv6Address.parse("::2"), new byte[]{1}));
        assertThrows(IllegalArgumentException.class, () -> Ipv6Codec.parse(java.util.Arrays.copyOf(wire, 40)));
    }
}
