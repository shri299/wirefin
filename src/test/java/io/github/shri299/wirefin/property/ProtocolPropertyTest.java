package io.github.shri299.wirefin.property;

import io.github.shri299.wirefin.icmp.*;
import io.github.shri299.wirefin.ipv4.*;
import io.github.shri299.wirefin.ipv6.*;
import io.github.shri299.wirefin.link.*;
import io.github.shri299.wirefin.tcp.*;
import io.github.shri299.wirefin.udp.*;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class ProtocolPropertyTest {
    private static final long SEED = 0x5749524546494eL;

    @Test void validPacketsRoundTripAcrossAllExternallyVisibleCodecs() {
        Random random = new Random(SEED);
        for (int example = 0; example < 500; example++) {
            Ipv4Address source4 = new Ipv4Address(random.nextInt());
            Ipv4Address destination4 = new Ipv4Address(random.nextInt());
            Ipv6Address source6 = new Ipv6Address(bytes(random, 16));
            Ipv6Address destination6 = new Ipv6Address(bytes(random, 16));
            byte[] payload = bytes(random, random.nextInt(257));

            Ipv4Packet ipv4 = new Ipv4Packet(random.nextInt(256), random.nextInt(65_536),
                    random.nextBoolean() ? 0 : 2, 0, random.nextInt(256), random.nextInt(256),
                    source4, destination4, bytes(random, random.nextInt(11) * 4), payload);
            assertEquals(ipv4, Ipv4Codec.parse(Ipv4Codec.serialize(ipv4)));

            Ipv6Packet ipv6 = new Ipv6Packet(random.nextInt(256), random.nextInt(1 << 20),
                    random.nextInt(256), random.nextInt(256), source6, destination6, payload);
            assertEquals(ipv6, Ipv6Codec.parse(Ipv6Codec.serialize(ipv6)));

            TcpSegment tcp = new TcpSegment(random.nextInt(65_536), random.nextInt(65_536),
                    Integer.toUnsignedLong(random.nextInt()), Integer.toUnsignedLong(random.nextInt()),
                    random.nextInt(0x200), random.nextInt(65_536), random.nextInt(65_536),
                    new byte[0], payload);
            assertEquals(tcp, TcpCodec.parse(TcpCodec.serialize(tcp, source4, destination4), source4, destination4));

            UdpDatagram udp = new UdpDatagram(random.nextInt(65_536), random.nextInt(65_536), payload);
            assertEquals(udp, UdpCodec.parse(UdpCodec.serialize(udp, source6, destination6), source6, destination6));

            IcmpMessage icmp4 = new IcmpMessage(random.nextInt(256), random.nextInt(256), random.nextInt(), payload);
            assertEquals(icmp4, IcmpCodec.parseV4(IcmpCodec.serializeV4(icmp4)));
            IcmpMessage icmp6 = new IcmpMessage(128, 0, random.nextInt(), payload);
            assertEquals(icmp6, IcmpCodec.parseV6(IcmpCodec.serializeV6(icmp6, source6, destination6), source6, destination6));

            EthernetFrame ethernet = new EthernetFrame(new MacAddress(bytes(random, 6)),
                    new MacAddress(bytes(random, 6)), random.nextInt(65_536), payload);
            assertEquals(ethernet, EthernetCodec.parse(EthernetCodec.serialize(ethernet)));
        }
    }

    @Test void mutationOfProtectedBytesInvalidatesChecksums() {
        Random random = new Random(SEED ^ 0x434845434bL);
        Ipv4Address source = Ipv4Address.parse("192.0.2.1"), destination = Ipv4Address.parse("192.0.2.2");
        for (int example = 0; example < 250; example++) {
            byte[] payload = bytes(random, 1 + random.nextInt(256));
            byte[] ip = Ipv4Codec.serialize(new Ipv4Packet(0, example, 2, 0, 64, 6, source, destination,
                    new byte[0], payload));
            ip[8] ^= 1;
            assertThrows(IllegalArgumentException.class, () -> Ipv4Codec.parse(ip));

            byte[] tcp = TcpCodec.serialize(new TcpSegment(1, 2, 3, 4, TcpFlags.ACK, 4096, 0,
                    new byte[0], payload), source, destination);
            tcp[tcp.length - 1] ^= 1;
            assertThrows(IllegalArgumentException.class, () -> TcpCodec.parse(tcp, source, destination));

            byte[] udp = UdpCodec.serialize(new UdpDatagram(1, 2, payload), source, destination);
            udp[udp.length - 1] ^= 1;
            assertThrows(IllegalArgumentException.class, () -> UdpCodec.parse(udp, source, destination));

            byte[] icmp = IcmpCodec.serializeV4(new IcmpMessage(8, 0, 1, payload));
            icmp[icmp.length - 1] ^= 1;
            assertThrows(IllegalArgumentException.class, () -> IcmpCodec.parseV4(icmp));
        }
    }

    private static byte[] bytes(Random random, int length) {
        byte[] bytes = new byte[length];
        random.nextBytes(bytes);
        return bytes;
    }
}
