package io.github.shri299.wirefin.runtime;

import io.github.shri299.wirefin.ipv4.Ipv4Address;
import io.github.shri299.wirefin.ipv4.Ipv4Codec;
import io.github.shri299.wirefin.ipv4.Ipv4Packet;
import io.github.shri299.wirefin.tcp.TcpCodec;
import io.github.shri299.wirefin.tcp.TcpFlags;
import io.github.shri299.wirefin.tcp.TcpOptions;
import io.github.shri299.wirefin.tcp.TcpSegment;
import io.github.shri299.wirefin.tcp.connection.TcpConnection;
import io.github.shri299.wirefin.tcp.connection.TcpConnectionKey;
import io.github.shri299.wirefin.tcp.connection.TcpConnectionTable;
import io.github.shri299.wirefin.tcp.reliability.SequenceNumber;
import io.github.shri299.wirefin.tcp.state.TcpState;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.logging.Logger;

/** Pure packet-in/packet-out protocol core; it has no dependency on TUN or kernel sockets. */
public final class PacketProcessor {
    private static final Logger LOG = Logger.getLogger(PacketProcessor.class.getName());
    private final Ipv4Address localAddress;
    private final ConcurrentHashMap<Integer, ListenerState> listeners = new ConcurrentHashMap<>();
    private final TcpConnectionTable connections = new TcpConnectionTable();
    private final LongSupplier isnSource;
    private final Consumer<byte[]> asynchronousOutput;
    private final AtomicInteger nextIpIdentification = new AtomicInteger();
    private final AtomicInteger nextEphemeralPort = new AtomicInteger(49_152);
    private final LongSupplier nanoTime;
    private final TcpConnection.Config connectionConfig;
    private final long cookieSecret;

    public PacketProcessor(Ipv4Address localAddress) {
        this(localAddress, () -> ThreadLocalRandom.current().nextLong(1L << 32), ignored -> { });
    }
    public PacketProcessor(Ipv4Address localAddress, LongSupplier isnSource) { this(localAddress, isnSource, ignored -> { }); }
    public PacketProcessor(Ipv4Address localAddress, LongSupplier isnSource, Consumer<byte[]> asynchronousOutput) {
        this(localAddress, isnSource, asynchronousOutput, System::nanoTime, TcpConnection.Config.defaults());
    }
    public PacketProcessor(Ipv4Address localAddress, LongSupplier isnSource, Consumer<byte[]> asynchronousOutput,
                           LongSupplier nanoTime, TcpConnection.Config connectionConfig) {
        this.localAddress = localAddress; this.isnSource = isnSource; this.asynchronousOutput = asynchronousOutput;
        this.nanoTime = nanoTime; this.connectionConfig = connectionConfig;
        this.cookieSecret = ThreadLocalRandom.current().nextLong();
    }

    public void listen(int port) { listen(port, 128, false, ignored -> { }); }
    public void listen(int port, Consumer<TcpConnection> onEstablished) { listen(port, 128, false, onEstablished); }
    public void listen(int port, int backlog, boolean synCookies, Consumer<TcpConnection> onEstablished) {
        if (port < 1 || port > 65_535) throw new IllegalArgumentException("invalid listen port");
        if (backlog < 1) throw new IllegalArgumentException("backlog must be positive");
        if (listeners.putIfAbsent(port, new ListenerState(backlog, synCookies, onEstablished)) != null)
            throw new IllegalStateException("port already listening: " + port);
    }

    public TcpConnection connect(Ipv4Address remoteAddress, int remotePort) {
        if (remotePort < 1 || remotePort > 65_535) throw new IllegalArgumentException("invalid remote port");
        for (int attempts = 0; attempts < 16_384; attempts++) {
            int port = 49_152 + Math.floorMod(nextEphemeralPort.getAndIncrement() - 49_152, 16_384);
            TcpConnectionKey key = new TcpConnectionKey(localAddress, port, remoteAddress, remotePort);
            if (connections.find(key).isPresent()) continue;
            TcpConnection connection = connections.add(TcpConnection.activeOpen(
                    key, isnSource.getAsLong(), nanoTime.getAsLong(), connectionConfig));
            transmit(connection, List.of(connection.syn()));
            return connection;
        }
        throw new IllegalStateException("no ephemeral TCP ports available");
    }

    public List<byte[]> process(byte[] rawPacket) {
        final Ipv4Packet ip;
        final TcpSegment tcp;
        try {
            ip = Ipv4Codec.parse(rawPacket);
            if (!ip.destination().equals(localAddress) || ip.protocol() != Ipv4Packet.PROTOCOL_TCP || ip.isFragmented()) return List.of();
            tcp = TcpCodec.parse(ip.payload(), ip.source(), ip.destination());
        } catch (IllegalArgumentException malformed) {
            LOG.fine(() -> "Dropping malformed packet: " + malformed.getMessage()); return List.of();
        }
        LOG.fine(() -> "RX TCP src=" + ip.source() + ":" + tcp.sourcePort() + " dst=" + ip.destination() +
                ":" + tcp.destinationPort() + " flags=" + TcpFlags.describe(tcp.flags()) + " seq=" + tcp.sequenceNumber());
        TcpConnectionKey key = new TcpConnectionKey(localAddress, tcp.destinationPort(), ip.source(), tcp.sourcePort());
        TcpConnection connection = connections.find(key).orElse(null);
        List<TcpSegment> replies = new ArrayList<>();
        ListenerState listener = listeners.get(tcp.destinationPort());
        if (connection == null) {
            if (listener != null && tcp.has(TcpFlags.SYN) && !tcp.has(TcpFlags.ACK)) {
                if (listener.reserveHalfOpen(key)) {
                    connection = connections.add(TcpConnection.passiveOpen(key, isnSource.getAsLong(), tcp,
                            nanoTime.getAsLong(), connectionConfig));
                    replies.add(connection.synAck());
                } else if (listener.synCookies) replies.add(cookieSynAck(key, tcp));
            } else if (listener != null && listener.synCookies && tcp.has(TcpFlags.ACK) && validCookie(key, tcp)) {
                TcpSegment syntheticSyn = new TcpSegment(tcp.sourcePort(), tcp.destinationPort(),
                        SequenceNumber.add(tcp.sequenceNumber(), -1), 0, TcpFlags.SYN, tcp.windowSize(), 0,
                        new byte[0], new byte[0]);
                long cookie = SequenceNumber.add(tcp.acknowledgementNumber(), -1);
                connection = connections.add(TcpConnection.passiveOpen(key, cookie, syntheticSyn,
                        nanoTime.getAsLong(), connectionConfig));
                TcpConnection.ProcessingResult result = connection.receive(tcp, nanoTime.getAsLong());
                replies.addAll(result.outbound());
                if (result.justEstablished() && !listener.promoteCookie(connection)) {
                    connections.remove(key);
                    replies.clear();
                    replies.add(resetFor(tcp));
                }
            } else if (!tcp.has(TcpFlags.RST)) replies.add(resetFor(tcp));
        } else {
            TcpConnection.ProcessingResult result = connection.receive(tcp, nanoTime.getAsLong());
            replies.addAll(result.outbound());
            if (result.justEstablished() && listener != null) listener.promote(key, connection);
            if (result.closed()) { connections.remove(key); if (listener != null) listener.remove(key); }
        }
        TcpConnectionKey responseKey = key;
        return replies.stream().map(reply -> encode(reply, responseKey)).toList();
    }

    private TcpSegment cookieSynAck(TcpConnectionKey key, TcpSegment syn) {
        long cookie = cookie(key, cookieBucket(nanoTime.getAsLong()));
        return new TcpSegment(key.localPort(), key.remotePort(), cookie,
                SequenceNumber.add(syn.sequenceNumber(), 1), TcpFlags.SYN | TcpFlags.ACK,
                Math.min(65_535, connectionConfig.receiveCapacity()), 0,
                TcpOptions.mss(connectionConfig.localMss()), new byte[0]);
    }
    private boolean validCookie(TcpConnectionKey key, TcpSegment ack) {
        long value = SequenceNumber.add(ack.acknowledgementNumber(), -1);
        long bucket = cookieBucket(nanoTime.getAsLong());
        return value == cookie(key, bucket) || value == cookie(key, bucket - 1);
    }
    private static long cookieBucket(long nanos) { return nanos / 60_000_000_000L; }
    private long cookie(TcpConnectionKey key, long bucket) {
        long value = cookieSecret ^ Integer.toUnsignedLong(key.hashCode()) ^ bucket * 0x9E3779B97F4A7C15L;
        value ^= value >>> 33; value *= 0xff51afd7ed558ccdL; value ^= value >>> 33;
        return value & SequenceNumber.MASK;
    }

    private byte[] encode(TcpSegment segment, TcpConnectionKey key) {
        byte[] tcp = TcpCodec.serialize(segment, key.localAddress(), key.remoteAddress());
        Ipv4Packet ip = new Ipv4Packet(0, nextIpIdentification.getAndIncrement() & 0xffff, 2, 0, 64,
                Ipv4Packet.PROTOCOL_TCP, key.localAddress(), key.remoteAddress(), new byte[0], tcp);
        LOG.fine(() -> "TX TCP flags=" + TcpFlags.describe(segment.flags()) + " seq=" + segment.sequenceNumber() +
                " ack=" + segment.acknowledgementNumber() + " len=" + segment.payload().length);
        return Ipv4Codec.serialize(ip);
    }
    private static TcpSegment resetFor(TcpSegment incoming) {
        if (incoming.has(TcpFlags.ACK)) return new TcpSegment(incoming.destinationPort(), incoming.sourcePort(),
                incoming.acknowledgementNumber(), 0, TcpFlags.RST, 0, 0, new byte[0], new byte[0]);
        long acknowledgement = SequenceNumber.add(incoming.sequenceNumber(), incoming.sequenceSpaceLength());
        return new TcpSegment(incoming.destinationPort(), incoming.sourcePort(), 0, acknowledgement,
                TcpFlags.RST | TcpFlags.ACK, 0, 0, new byte[0], new byte[0]);
    }

    public TcpConnectionTable connections() { return connections; }
    public void transmit(TcpConnection connection, List<TcpSegment> segments) {
        for (TcpSegment segment : segments) asynchronousOutput.accept(encode(segment, connection.key()));
    }
    public void pollRetransmissions(long nowNanos) {
        for (TcpConnection connection : connections.snapshot()) {
            transmit(connection, connection.retransmissionsDue(nowNanos));
            if (connection.expireTimeWait(nowNanos) || connection.state() == TcpState.CLOSED) {
                connections.remove(connection.key());
                ListenerState listener = listeners.get(connection.key().localPort());
                if (listener != null) listener.remove(connection.key());
            }
        }
    }
    public void accepted(int port) { ListenerState listener = listeners.get(port); if (listener != null) listener.accepted(); }
    public int halfOpenCount(int port) { ListenerState listener = listeners.get(port); return listener == null ? 0 : listener.halfOpenCount(); }
    public int establishedBacklogCount(int port) { ListenerState listener = listeners.get(port); return listener == null ? 0 : listener.establishedCount(); }

    private static final class ListenerState {
        private final int backlog;
        private final boolean synCookies;
        private final Consumer<TcpConnection> callback;
        private final Set<TcpConnectionKey> halfOpen = new HashSet<>();
        private int established;
        private ListenerState(int backlog, boolean synCookies, Consumer<TcpConnection> callback) {
            this.backlog = backlog; this.synCookies = synCookies; this.callback = callback;
        }
        synchronized boolean reserveHalfOpen(TcpConnectionKey key) {
            if (halfOpen.size() + established >= backlog) return false;
            return halfOpen.add(key);
        }
        synchronized void promote(TcpConnectionKey key, TcpConnection connection) {
            halfOpen.remove(key); established++; callback.accept(connection);
        }
        synchronized boolean promoteCookie(TcpConnection connection) {
            if (established >= backlog) return false;
            established++; callback.accept(connection); return true;
        }
        synchronized void accepted() { if (established > 0) established--; }
        synchronized void remove(TcpConnectionKey key) { halfOpen.remove(key); }
        synchronized int halfOpenCount() { return halfOpen.size(); }
        synchronized int establishedCount() { return established; }
    }
}
