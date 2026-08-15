package io.github.shri299.wirefin.ipv4;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class Ipv4FragmentReassemblerTest {
    private final Ipv4Address a = Ipv4Address.parse("192.0.2.1"), b = Ipv4Address.parse("192.0.2.2");
    private Ipv4Packet part(int offset, int more, byte[] data) {
        return new Ipv4Packet(0, 9, more, offset, 64, 17, a, b, new byte[0], data);
    }
    @Test void reassemblesOutOfOrder() {
        var r = new Ipv4FragmentReassembler();
        assertTrue(r.accept(part(1, 0, new byte[]{9, 10}), 1).isEmpty());
        var complete = r.accept(part(0, 1, new byte[]{1,2,3,4,5,6,7,8}), 2).orElseThrow();
        assertArrayEquals(new byte[]{1,2,3,4,5,6,7,8,9,10}, complete.payload());
        assertFalse(complete.isFragmented());
    }
    @Test void overlapRejectsWholeDatagramAndTimeoutIsBounded() {
        var r = new Ipv4FragmentReassembler(1, 32, 10);
        r.accept(part(0, 1, new byte[]{1,2,3,4,5,6,7,8}), 0);
        assertTrue(r.accept(part(0, 1, new byte[]{9,9,9,9,9,9,9,9}), 1).isEmpty()); assertEquals(0, r.pendingDatagrams());
        r.accept(part(0, 1, new byte[]{1,2,3,4,5,6,7,8}), 2); r.expire(12); assertEquals(0, r.pendingDatagrams());
    }
}
