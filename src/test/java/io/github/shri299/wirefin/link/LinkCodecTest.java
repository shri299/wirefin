package io.github.shri299.wirefin.link;

import io.github.shri299.wirefin.ipv4.Ipv4Address;
import io.github.shri299.wirefin.ipv6.Ipv6Address;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LinkCodecTest {
    @Test void ethernetAndArpRoundTrip() {
        var a = MacAddress.parse("02:00:00:00:00:01"), b = MacAddress.parse("02:00:00:00:00:02");
        var frame = new EthernetFrame(b, a, EthernetFrame.IPV4, new byte[]{1,2,3});
        assertEquals(frame, EthernetCodec.parse(EthernetCodec.serialize(frame)));
        var arp = new ArpPacket(ArpPacket.REQUEST, a, Ipv4Address.parse("192.0.2.1"),
                new MacAddress(new byte[6]), Ipv4Address.parse("192.0.2.2"));
        assertEquals(arp, ArpCodec.parse(ArpCodec.serialize(arp)));
    }
    @Test void neighborDiscoveryOptionsRoundTrip() {
        var target = Ipv6Address.parse("2001:db8::2"); var mac = MacAddress.parse("02:00:00:00:00:02");
        var solicitation = NeighborDiscovery.parse(NeighborDiscovery.solicitation(target, mac));
        assertEquals(135, solicitation.type()); assertEquals(target, solicitation.target()); assertEquals(mac, solicitation.linkLayerAddress());
        var advertisement = NeighborDiscovery.parse(NeighborDiscovery.advertisement(target, mac, true, true));
        assertEquals(136, advertisement.type()); assertNotEquals(0, advertisement.flags());
    }
}
