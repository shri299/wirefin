package io.github.shri299.wirefin.fuzz;

import io.github.shri299.wirefin.icmp.IcmpCodec;
import io.github.shri299.wirefin.ipv4.*;
import io.github.shri299.wirefin.ipv6.*;
import io.github.shri299.wirefin.link.EthernetCodec;
import io.github.shri299.wirefin.runtime.PacketProcessor;
import io.github.shri299.wirefin.tcp.*;
import io.github.shri299.wirefin.udp.UdpCodec;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.fail;

class ParserFuzzTest {
    private static final long SEED = 0x46555a5a57495245L;
    private static final Ipv4Address V4A = Ipv4Address.parse("192.0.2.1"), V4B = Ipv4Address.parse("192.0.2.2");
    private static final Ipv6Address V6A = Ipv6Address.parse("2001:db8::1"), V6B = Ipv6Address.parse("2001:db8::2");

    @Test void arbitraryWireInputIsAcceptedOrRejectedWithDocumentedParseErrors() {
        Random random = new Random(SEED);
        PacketProcessor processor = new PacketProcessor(V4B);
        for (int example = 0; example < 5_000; example++) {
            byte[] input = new byte[random.nextInt(2_049)];
            random.nextBytes(input);
            expectSafe("Ethernet", example, () -> EthernetCodec.parse(input));
            expectSafe("IPv4", example, () -> Ipv4Codec.parse(input));
            expectSafe("IPv6", example, () -> Ipv6Codec.parse(input));
            expectSafe("TCPv4", example, () -> TcpCodec.parse(input, V4A, V4B));
            expectSafe("TCPv6", example, () -> TcpCodec.parse(input, V6A, V6B));
            expectSafe("UDPv4", example, () -> UdpCodec.parse(input, V4A, V4B));
            expectSafe("UDPv6", example, () -> UdpCodec.parse(input, V6A, V6B));
            expectSafe("ICMPv4", example, () -> IcmpCodec.parseV4(input));
            expectSafe("ICMPv6", example, () -> IcmpCodec.parseV6(input, V6A, V6B));
            expectSafe("TCP options", example, () -> TcpOptions.parse(input));
            try {
                processor.process(input);
            } catch (RuntimeException failure) {
                fail("packet processor escaped fuzz input example=" + example + " seed=" + SEED, failure);
            }
        }
    }

    private static void expectSafe(String target, int example, Runnable parser) {
        try {
            parser.run();
        } catch (IllegalArgumentException expected) {
            // Public codecs use IllegalArgumentException subclasses for malformed wire input.
        } catch (RuntimeException failure) {
            fail(target + " escaped unexpected exception example=" + example + " seed=" + SEED, failure);
        }
    }
}
