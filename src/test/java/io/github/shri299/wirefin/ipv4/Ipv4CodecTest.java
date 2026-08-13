package io.github.shri299.wirefin.ipv4;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class Ipv4CodecTest {
    @Test void roundTripsAndValidatesChecksum() {
        var packet = new Ipv4Packet(0, 42, 2, 0, 64, 6,
                Ipv4Address.parse("10.0.0.1"), Ipv4Address.parse("10.0.0.2"), new byte[0], new byte[]{1,2,3});
        byte[] wire = Ipv4Codec.serialize(packet);
        assertEquals(0, InternetChecksum.compute(wire, 0, 20, 0));
        assertEquals(packet, Ipv4Codec.parse(wire));
    }

    @Test void rejectsBadChecksumAndLengths() {
        var packet = new Ipv4Packet(0, 1, 0, 0, 64, 6,
                Ipv4Address.parse("1.2.3.4"), Ipv4Address.parse("5.6.7.8"), new byte[0], new byte[0]);
        byte[] wire = Ipv4Codec.serialize(packet);
        wire[8] ^= 1;
        assertThrows(Ipv4Codec.MalformedPacketException.class, () -> Ipv4Codec.parse(wire));
        assertThrows(Ipv4Codec.MalformedPacketException.class, () -> Ipv4Codec.parse(new byte[19]));
    }

    @Test void checksumHandlesOddLength() {
        assertEquals(0xfbfd, InternetChecksum.compute(new byte[]{1,2,3}));
    }
}
