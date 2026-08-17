package io.github.shri299.wirefin.link;

import io.github.shri299.wirefin.ip.IpAddress;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Bounded learned L3-to-L2 mapping used only by Ethernet backends. */
public final class NeighborCache {
    private final int capacity; private final ConcurrentHashMap<IpAddress, Entry> entries = new ConcurrentHashMap<>();
    public NeighborCache(int capacity) { if (capacity < 1) throw new IllegalArgumentException("capacity must be positive"); this.capacity = capacity; }
    public void learn(IpAddress address, MacAddress mac, long expiresAtNanos) {
        if (entries.size() >= capacity && !entries.containsKey(address)) entries.entrySet().stream().min(java.util.Comparator.comparingLong(e -> e.getValue().expiresAt)).ifPresent(e -> entries.remove(e.getKey(), e.getValue()));
        entries.put(address, new Entry(mac, expiresAtNanos));
    }
    public Optional<MacAddress> lookup(IpAddress address, long nowNanos) {
        Entry entry = entries.get(address); if (entry == null) return Optional.empty();
        if (nowNanos >= entry.expiresAt) { entries.remove(address, entry); return Optional.empty(); }
        return Optional.of(entry.mac);
    }
    private record Entry(MacAddress mac, long expiresAt) {}
}
