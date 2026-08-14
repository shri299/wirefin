package io.github.shri299.wirefin.routing;

import io.github.shri299.wirefin.ipv4.Ipv4Address;
import io.github.shri299.wirefin.ipv6.Ipv6Address;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RouteTableTest {
    @Test void selectsLongestPrefixWithinFamily() {
        var table = new RouteTable<String>();
        table.add(Ipv4Address.parse("0.0.0.0"), 0, "v4-default");
        table.add(Ipv4Address.parse("10.2.0.0"), 16, "v4-specific");
        table.add(Ipv6Address.parse("::"), 0, "v6-default");
        table.add(Ipv6Address.parse("2001:db8:1::"), 48, "v6-specific");
        assertEquals("v4-specific", table.lookup(Ipv4Address.parse("10.2.3.4")).orElseThrow().target());
        assertEquals("v6-specific", table.lookup(Ipv6Address.parse("2001:db8:1::9")).orElseThrow().target());
    }
}
