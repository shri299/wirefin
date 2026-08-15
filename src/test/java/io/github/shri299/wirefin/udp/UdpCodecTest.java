package io.github.shri299.wirefin.udp;

import io.github.shri299.wirefin.ipv4.Ipv4Address;
import io.github.shri299.wirefin.ipv6.Ipv6Address;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UdpCodecTest {
    @Test void roundTripsIpv4AndIpv6Checksums() {
        var datagram = new UdpDatagram(1234, 4321, "hello".getBytes());
        var a4 = Ipv4Address.parse("192.0.2.1"); var b4 = Ipv4Address.parse("192.0.2.2");
        assertEquals(datagram, UdpCodec.parse(UdpCodec.serialize(datagram, a4, b4), a4, b4));
        var a6 = Ipv6Address.parse("2001:db8::1"); var b6 = Ipv6Address.parse("2001:db8::2");
        assertEquals(datagram, UdpCodec.parse(UdpCodec.serialize(datagram, a6, b6), a6, b6));
    }
    @Test void ipv6RejectsZeroAndCorruptChecksums() {
        var a = Ipv6Address.parse("2001:db8::1"); var b = Ipv6Address.parse("2001:db8::2");
        byte[] zero = UdpCodec.serialize(new UdpDatagram(1, 2, new byte[]{3}), a, b);
        zero[6] = zero[7] = 0;
        assertThrows(IllegalArgumentException.class, () -> UdpCodec.parse(zero, a, b));
        byte[] corrupt = UdpCodec.serialize(new UdpDatagram(1, 2, new byte[]{3}), a, b); corrupt[8] ^= 1;
        assertThrows(IllegalArgumentException.class, () -> UdpCodec.parse(corrupt, a, b));
    }
}
