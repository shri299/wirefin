package io.github.shri299.wirefin.tcp;

import io.github.shri299.wirefin.ipv4.Ipv4Address;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TcpCodecTest {
    private static final Ipv4Address SOURCE = Ipv4Address.parse("10.0.0.1");
    private static final Ipv4Address DESTINATION = Ipv4Address.parse("10.0.0.2");

    @Test void roundTripsHeaderOptionsPayloadAndChecksum() {
        var segment = new TcpSegment(52194, 8080, 0xffff_fffeL, 1001,
                TcpFlags.ACK | TcpFlags.PSH, 32768, 0, new byte[]{2,4,5,(byte)180}, "hello".getBytes());
        byte[] wire = TcpCodec.serialize(segment, SOURCE, DESTINATION);
        assertTrue(TcpCodec.checksumValid(wire, SOURCE, DESTINATION));
        assertEquals(segment, TcpCodec.parse(wire, SOURCE, DESTINATION));
    }

    @Test void rejectsMalformedAndCorruptSegments() {
        assertThrows(TcpCodec.MalformedSegmentException.class,
                () -> TcpCodec.parse(new byte[19], SOURCE, DESTINATION));
        var segment = new TcpSegment(1, 2, 3, 4, TcpFlags.ACK, 100, 0, new byte[0], new byte[0]);
        byte[] wire = TcpCodec.serialize(segment, SOURCE, DESTINATION);
        wire[5] ^= 1;
        assertThrows(TcpCodec.MalformedSegmentException.class, () -> TcpCodec.parse(wire, SOURCE, DESTINATION));
    }

    @Test void synAndFinConsumeSequenceSpace() {
        var segment = new TcpSegment(1, 2, 0, 0, TcpFlags.SYN | TcpFlags.FIN, 1, 0, new byte[0], new byte[]{1,2});
        assertEquals(4, segment.sequenceSpaceLength());
    }
}
