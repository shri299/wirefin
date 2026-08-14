package io.github.shri299.wirefin.routing;

import io.github.shri299.wirefin.ip.IpAddress;
import java.util.*;

/** Family-safe longest-prefix-match table. The route target is caller-defined. */
public final class RouteTable<T> {
    private final List<Route<T>> routes = new ArrayList<>();
    public synchronized void add(IpAddress prefix, int length, T target) {
        if (prefix == null || target == null || length < 0 || length > prefix.bitLength()) throw new IllegalArgumentException("invalid route");
        routes.add(new Route<>(prefix, length, target));
    }
    public synchronized Optional<Route<T>> lookup(IpAddress address) {
        return routes.stream().filter(r -> r.matches(address)).max(Comparator.comparingInt(Route::prefixLength));
    }
    public record Route<T>(IpAddress prefix, int prefixLength, T target) {
        boolean matches(IpAddress address) {
            if (address.bitLength() != prefix.bitLength()) return false;
            byte[] a = address.bytes(), p = prefix.bytes(); int full = prefixLength / 8, bits = prefixLength % 8;
            for (int i = 0; i < full; i++) if (a[i] != p[i]) return false;
            return bits == 0 || ((a[full] ^ p[full]) & (0xff << (8 - bits))) == 0;
        }
    }
}
