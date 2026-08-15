package io.github.shri299.wirefin.icmp;

import io.github.shri299.wirefin.ipv6.Ipv6Address;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class IcmpCodecTest {
    @Test void echoFieldsAndChecksumsRoundTrip() {
        var message = new IcmpMessage(8, 0, 0x12340007, "ping".getBytes());
        var decoded = IcmpCodec.parseV4(IcmpCodec.serializeV4(message));
        assertEquals(0x1234, decoded.identifier()); assertEquals(7, decoded.sequence()); assertEquals(message, decoded);
        var a = Ipv6Address.parse("2001:db8::1"); var b = Ipv6Address.parse("2001:db8::2");
        var v6 = new IcmpMessage(128, 0, 42, new byte[]{1, 2});
        assertEquals(v6, IcmpCodec.parseV6(IcmpCodec.serializeV6(v6, a, b), a, b));
    }
}
