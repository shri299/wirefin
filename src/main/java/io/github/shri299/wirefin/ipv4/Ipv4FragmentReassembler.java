package io.github.shri299.wirefin.ipv4;

import java.util.*;

/** Bounded RFC 791 fragment reassembly with whole-datagram overlap rejection. */
public final class Ipv4FragmentReassembler {
    private final int maxDatagrams, maxBytes;
    private final long timeoutNanos;
    private final LinkedHashMap<Key, Assembly> assemblies = new LinkedHashMap<>();
    private int retainedBytes;
    public Ipv4FragmentReassembler() { this(64, 1 << 20, 30_000_000_000L); }
    public Ipv4FragmentReassembler(int maxDatagrams, int maxBytes, long timeoutNanos) {
        if (maxDatagrams < 1 || maxBytes < 1 || timeoutNanos < 1) throw new IllegalArgumentException("invalid reassembly bounds");
        this.maxDatagrams = maxDatagrams; this.maxBytes = maxBytes; this.timeoutNanos = timeoutNanos;
    }
    public synchronized Optional<Ipv4Packet> accept(Ipv4Packet fragment, long now) {
        expire(now);
        if (!fragment.isFragmented()) return Optional.of(fragment);
        int start = fragment.fragmentOffset() * 8, end = start + fragment.payload().length;
        if ((fragment.flags() & 1) != 0 && (fragment.payload().length & 7) != 0 || end > 65_535)
            return Optional.empty();
        Key key = new Key(fragment.source(), fragment.destination(), fragment.identification(), fragment.protocol());
        Assembly assembly = assemblies.computeIfAbsent(key, ignored -> new Assembly(fragment, now));
        for (Part part : assembly.parts) if (start < part.end && end > part.start) { remove(key, assembly); return Optional.empty(); }
        byte[] payload = fragment.payload(); assembly.parts.add(new Part(start, end, payload));
        assembly.updated = now; retainedBytes += payload.length;
        if ((fragment.flags() & 1) == 0) {
            if (assembly.total >= 0 && assembly.total != end) { remove(key, assembly); return Optional.empty(); }
            assembly.total = end;
        }
        enforceBounds();
        if (!assemblies.containsKey(key) || assembly.total < 0) return Optional.empty();
        assembly.parts.sort(Comparator.comparingInt(p -> p.start));
        int cursor = 0; for (Part part : assembly.parts) { if (part.start != cursor) return Optional.empty(); cursor = part.end; }
        if (cursor != assembly.total) return Optional.empty();
        byte[] joined = new byte[assembly.total]; for (Part part : assembly.parts) System.arraycopy(part.bytes, 0, joined, part.start, part.bytes.length);
        remove(key, assembly);
        Ipv4Packet first = assembly.template;
        return Optional.of(new Ipv4Packet(first.dscpEcn(), first.identification(), first.flags() & 2, 0,
                first.ttl(), first.protocol(), first.source(), first.destination(), first.options(), joined));
    }
    public synchronized void expire(long now) {
        var iterator = assemblies.entrySet().iterator();
        while (iterator.hasNext()) { var entry = iterator.next(); if (now - entry.getValue().updated >= timeoutNanos) {
            retainedBytes -= entry.getValue().bytes(); iterator.remove();
        }}
    }
    public synchronized int pendingDatagrams() { return assemblies.size(); }
    private void enforceBounds() {
        while (assemblies.size() > maxDatagrams || retainedBytes > maxBytes) {
            var oldest = assemblies.entrySet().iterator().next(); remove(oldest.getKey(), oldest.getValue());
        }
    }
    private void remove(Key key, Assembly value) { if (assemblies.remove(key) != null) retainedBytes -= value.bytes(); }
    private record Key(Ipv4Address source, Ipv4Address destination, int id, int protocol) {}
    private record Part(int start, int end, byte[] bytes) {}
    private static final class Assembly {
        final Ipv4Packet template; final List<Part> parts = new ArrayList<>(); long updated; int total = -1;
        Assembly(Ipv4Packet template, long updated) { this.template = template; this.updated = updated; }
        int bytes() { return parts.stream().mapToInt(p -> p.bytes.length).sum(); }
    }
}
