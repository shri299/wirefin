package io.github.shri299.wirefin.trace;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class PcapNgWriterTest {
    @Test void writesReadableSectionInterfacesDirectionsAndPackets() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (PcapNgWriter writer = new PcapNgWriter(bytes)) {
            writer.record(123, PacketCapture.Direction.RX, new byte[] {0x45, 1, 2});
            writer.record(456, PacketCapture.Direction.TX, new byte[] {0x60, 3});
        }
        byte[] capture = bytes.toByteArray();
        ByteBuffer input = ByteBuffer.wrap(capture).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(0x0a0d0d0a, input.getInt());
        assertTrue(new String(capture, StandardCharsets.ISO_8859_1).contains("wirefin-rx"));
        assertTrue(new String(capture, StandardCharsets.ISO_8859_1).contains("wirefin-tx"));
        int enhancedBlocks = 0;
        for (int offset = 0; offset < capture.length;) {
            int type = input.getInt(offset), length = input.getInt(offset + 4);
            assertTrue(length >= 12 && (length & 3) == 0);
            assertEquals(length, input.getInt(offset + length - 4));
            if (type == 6) enhancedBlocks++;
            offset += length;
        }
        assertEquals(2, enhancedBlocks);
    }
}
