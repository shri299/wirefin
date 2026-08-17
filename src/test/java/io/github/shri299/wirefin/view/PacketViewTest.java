package io.github.shri299.wirefin.view;

import io.github.shri299.wirefin.ipv4.*;
import io.github.shri299.wirefin.ipv6.*;
import io.github.shri299.wirefin.udp.*;
import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import static org.junit.jupiter.api.Assertions.*;

class PacketViewTest {
    @Test void ipv4UdpViewsReferenceExistingMemory() {
        Ipv4Address source=Ipv4Address.parse("192.0.2.1"),destination=Ipv4Address.parse("192.0.2.2");
        byte[] udp=UdpCodec.serialize(new UdpDatagram(1,2,new byte[]{3,4}),source,destination);
        byte[] wire=Ipv4Codec.serialize(new Ipv4Packet(0,1,2,0,64,17,source,destination,new byte[0],udp));
        var ip=Ipv4PacketView.parse(ByteBuffer.wrap(wire),0,wire.length); var datagram=UdpDatagramView.parse(ip.payload(),source,destination);
        assertEquals(17,ip.protocol());assertEquals(1,datagram.sourcePort());assertEquals(2,datagram.payload().length());
    }
    @Test void ipv6ViewValidatesLength() {
        Ipv6Address a=Ipv6Address.parse("2001:db8::1"),b=Ipv6Address.parse("2001:db8::2");
        byte[] wire=Ipv6Codec.serialize(new Ipv6Packet(0,0,17,64,a,b,new byte[]{1}));
        var view=Ipv6PacketView.parse(ByteBuffer.wrap(wire),0,wire.length);assertEquals(a,view.source());assertEquals(1,view.payload().length());
        assertThrows(IllegalArgumentException.class,()->Ipv6PacketView.parse(ByteBuffer.wrap(wire),0,40));
    }
}
