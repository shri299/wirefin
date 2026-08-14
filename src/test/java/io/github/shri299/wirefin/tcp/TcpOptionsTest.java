package io.github.shri299.wirefin.tcp;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TcpOptionsTest {
    @Test void findsMssAmongNopsAndOtherOptions() {
        byte[] options = {1, 3, 3, 7, 1, 2, 4, 0x05, (byte) 0xb4, 0, 0, 0};
        assertEquals(1460, TcpOptions.maximumSegmentSize(options).orElseThrow());
    }
    @Test void rejectsTruncatedOrZeroMss() {
        assertTrue(TcpOptions.maximumSegmentSize(new byte[]{2, 4, 1, 0}).isPresent());
        assertTrue(TcpOptions.maximumSegmentSize(new byte[]{2, 4, 0, 0}).isEmpty());
        assertTrue(TcpOptions.maximumSegmentSize(new byte[]{2, 4, 1}).isEmpty());
    }

    @Test void roundTripsAdvancedNegotiationAndSackOptions() {
        var syn = TcpOptions.parse(TcpOptions.syn(1460, 7, true, 1234L));
        assertEquals(1460, syn.maximumSegmentSize().orElseThrow());
        assertEquals(7, syn.windowScale().orElseThrow());
        assertTrue(syn.sackPermitted());
        assertEquals(1234, syn.timestamp().orElseThrow().value());
        assertEquals(0, syn.timestamp().orElseThrow().echoReply());

        var blocks = java.util.List.of(new TcpOptions.SackBlock(100, 200), new TcpOptions.SackBlock(300, 400));
        var established = TcpOptions.parse(TcpOptions.established(new TcpOptions.Timestamp(50, 40), blocks));
        assertEquals(new TcpOptions.Timestamp(50, 40), established.timestamp().orElseThrow());
        assertEquals(blocks, established.sackBlocks());
    }
}
