package io.github.shri299.wirefin.tcp.connection;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReceiveBufferTest {
    @Test void fillsAnAlreadyAdvertisedHoleAfterHigherOutOfOrderBytes() throws Exception {
        ReceiveBuffer buffer = new ReceiveBuffer(100, 16);
        buffer.accept(108, bytes("ijklmnop"));
        buffer.accept(100, bytes("abcdefgh"));
        byte[] delivered = new byte[16];
        assertEquals(16, buffer.read(delivered, 0, delivered.length));
        assertArrayEquals(bytes("abcdefghijklmnop"), delivered);
    }

    @Test void normalizesDuplicatesLeftRightAndSpanningOverlaps() throws Exception {
        ReceiveBuffer buffer = new ReceiveBuffer(100, 16);
        buffer.accept(103, bytes("def"));
        buffer.accept(102, bytes("cde")); // overlaps buffered right side
        buffer.accept(98, bytes("zzabcd")); // left overlap and spans RCV.NXT
        buffer.accept(100, bytes("abcdef")); // full duplicate
        byte[] result = new byte[6];
        assertEquals(6, buffer.read(result, 0, result.length));
        assertEquals("abcdef", new String(result));
        assertEquals(106, buffer.receiveNext());
    }

    @Test void windowShrinksForReadableAndOutOfOrderBytesThenReopens() throws Exception {
        ReceiveBuffer buffer = new ReceiveBuffer(100, 8);
        buffer.accept(104, bytes("ef"));
        assertEquals(6, buffer.advertisedWindow());
        buffer.accept(100, bytes("abcd"));
        assertEquals(2, buffer.advertisedWindow());
        byte[] consumed = new byte[3];
        assertEquals(3, buffer.read(consumed, 0, 3));
        assertEquals(5, buffer.advertisedWindow());
    }

    @Test void rejectsBytesOutsideCurrentWindow() throws Exception {
        ReceiveBuffer buffer = new ReceiveBuffer(100, 4);
        buffer.accept(104, bytes("x"));
        assertEquals(4, buffer.advertisedWindow());
        buffer.accept(100, bytes("abcd"));
        byte[] result = new byte[4];
        assertEquals(4, buffer.read(result, 0, 4));
        assertEquals("abcd", new String(result));
    }

    private static byte[] bytes(String text) { return text.getBytes(java.nio.charset.StandardCharsets.US_ASCII); }
}
