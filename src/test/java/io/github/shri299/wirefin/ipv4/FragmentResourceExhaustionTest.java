package io.github.shri299.wirefin.ipv4;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FragmentResourceExhaustionTest {
    @Test void fragmentFloodCannotExceedDatagramOrByteBounds() {
        Ipv4FragmentReassembler reassembler=new Ipv4FragmentReassembler(8,128,1_000);
        Ipv4Address source=Ipv4Address.parse("192.0.2.1"),destination=Ipv4Address.parse("192.0.2.2");
        for(int id=0;id<10_000;id++){
            reassembler.accept(new Ipv4Packet(0,id&0xffff,1,0,64,17,source,destination,new byte[0],new byte[64]),id);
            assertTrue(reassembler.pendingDatagrams()<=8);
            assertTrue(reassembler.retainedBytes()<=128);
        }
        reassembler.expire(20_000);
        assertEquals(0,reassembler.pendingDatagrams());assertEquals(0,reassembler.retainedBytes());
    }
}
