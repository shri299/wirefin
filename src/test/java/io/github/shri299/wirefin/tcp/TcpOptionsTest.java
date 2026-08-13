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
}
