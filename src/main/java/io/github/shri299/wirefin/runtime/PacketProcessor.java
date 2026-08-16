package io.github.shri299.wirefin.runtime;

import io.github.shri299.wirefin.icmp.IcmpCodec;
import io.github.shri299.wirefin.icmp.IcmpMessage;
import io.github.shri299.wirefin.ip.IpAddress;
import io.github.shri299.wirefin.ip.IpProtocolDispatcher;
import io.github.shri299.wirefin.ipv4.Ipv4Address;
import io.github.shri299.wirefin.ipv4.Ipv4Codec;
import io.github.shri299.wirefin.ipv4.Ipv4FragmentReassembler;
import io.github.shri299.wirefin.ipv4.Ipv4Packet;
import io.github.shri299.wirefin.ipv6.Ipv6Address;
import io.github.shri299.wirefin.ipv6.Ipv6Codec;
import io.github.shri299.wirefin.ipv6.Ipv6Packet;
import io.github.shri299.wirefin.socket.UdpReceivedDatagram;
import io.github.shri299.wirefin.metrics.NetworkMetrics;
import io.github.shri299.wirefin.tcp.TcpCodec;
import io.github.shri299.wirefin.tcp.TcpFlags;
import io.github.shri299.wirefin.tcp.TcpOptions;
import io.github.shri299.wirefin.tcp.TcpSegment;
import io.github.shri299.wirefin.tcp.connection.TcpConnection;
import io.github.shri299.wirefin.tcp.connection.TcpConnectionKey;
import io.github.shri299.wirefin.tcp.connection.TcpConnectionTable;
import io.github.shri299.wirefin.tcp.reliability.SequenceNumber;
import io.github.shri299.wirefin.tcp.state.TcpState;
import io.github.shri299.wirefin.udp.UdpCodec;
import io.github.shri299.wirefin.udp.UdpDatagram;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.Collection;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.logging.Logger;

/** Pure packet-in/packet-out protocol core; it has no dependency on TUN or kernel sockets. */
public final class PacketProcessor {
    private static final Logger LOG = Logger.getLogger(PacketProcessor.class.getName());
    private final Map<Integer, IpAddress> localAddresses;
    private final IpProtocolDispatcher dispatcher = new IpProtocolDispatcher();
    private final Ipv4FragmentReassembler fragments = new Ipv4FragmentReassembler();
    private final ConcurrentHashMap<Integer, Predicate<UdpReceivedDatagram>> udpBindings = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, ListenerState> listeners = new ConcurrentHashMap<>();
    private final TcpConnectionTable connections = new TcpConnectionTable();
    private final LongSupplier isnSource;
    private final Consumer<byte[]> asynchronousOutput;
    private final AtomicInteger nextIpIdentification = new AtomicInteger();
    private final AtomicInteger nextEphemeralPort = new AtomicInteger(49_152);
    private final LongSupplier nanoTime;
    private final TcpConnection.Config connectionConfig;
    private final long cookieSecret;
    private final NetworkMetrics metrics;

    public PacketProcessor(Ipv4Address localAddress) {
        this(localAddress, () -> ThreadLocalRandom.current().nextLong(1L << 32), ignored -> { });
    }
    public PacketProcessor(Ipv4Address localAddress, LongSupplier isnSource) { this(localAddress, isnSource, ignored -> { }); }
    public PacketProcessor(Ipv4Address localAddress, LongSupplier isnSource, Consumer<byte[]> asynchronousOutput) {
        this(localAddress, isnSource, asynchronousOutput, System::nanoTime, TcpConnection.Config.defaults());
    }
    public PacketProcessor(Ipv4Address localAddress, LongSupplier isnSource, Consumer<byte[]> asynchronousOutput,
                           LongSupplier nanoTime, TcpConnection.Config connectionConfig) {
        this(List.of(localAddress), isnSource, asynchronousOutput, nanoTime, connectionConfig);
    }
    public PacketProcessor(Collection<? extends IpAddress> localAddresses, LongSupplier isnSource,
                           Consumer<byte[]> asynchronousOutput, LongSupplier nanoTime,
                           TcpConnection.Config connectionConfig) {
        this(localAddresses, isnSource, asynchronousOutput, nanoTime, connectionConfig, new NetworkMetrics());
    }
    public PacketProcessor(Collection<? extends IpAddress> localAddresses, LongSupplier isnSource,
                           Consumer<byte[]> asynchronousOutput, LongSupplier nanoTime,
                           TcpConnection.Config connectionConfig, NetworkMetrics metrics) {
        if (localAddresses == null || localAddresses.isEmpty()) throw new IllegalArgumentException("local address required");
        var map = new java.util.HashMap<Integer, IpAddress>();
        for (IpAddress address : localAddresses) {
            if (map.putIfAbsent(address.bitLength(), address) != null)
                throw new IllegalArgumentException("only one local address per IP family is supported");
        }
        this.localAddresses = Map.copyOf(map); this.isnSource = isnSource; this.asynchronousOutput = asynchronousOutput;
        this.nanoTime = nanoTime; this.connectionConfig = connectionConfig;
        this.metrics = java.util.Objects.requireNonNull(metrics);
        this.cookieSecret = ThreadLocalRandom.current().nextLong();
        dispatcher.register(6, this::processTcp);
        dispatcher.register(17, this::processUdp);
        dispatcher.register(1, this::processIcmpV4);
        dispatcher.register(58, this::processIcmpV6);
    }

    public void listen(int port) { listen(port, 128, false, ignored -> { }); }
    public void listen(int port, Consumer<TcpConnection> onEstablished) { listen(port, 128, false, onEstablished); }
    public void listen(int port, int backlog, boolean synCookies, Consumer<TcpConnection> onEstablished) {
        if (port < 1 || port > 65_535) throw new IllegalArgumentException("invalid listen port");
        if (backlog < 1) throw new IllegalArgumentException("backlog must be positive");
        if (listeners.putIfAbsent(port, new ListenerState(backlog, synCookies, onEstablished)) != null)
            throw new IllegalStateException("port already listening: " + port);
    }

    public TcpConnection connect(IpAddress remoteAddress, int remotePort) {
        if (remotePort < 1 || remotePort > 65_535) throw new IllegalArgumentException("invalid remote port");
        IpAddress localAddress = localAddresses.get(remoteAddress.bitLength());
        if (localAddress == null) throw new IllegalArgumentException("no local address for remote IP family");
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
        try {
            if (rawPacket.length == 0) return List.of();
            int version = (rawPacket[0] >>> 4) & 0xf;
            if (version == 4) {
                Ipv4Packet parsed = Ipv4Codec.parse(rawPacket);
                IpAddress local = localAddresses.get(32);
                if (!parsed.destination().equals(local)) return List.of();
                var complete = fragments.accept(parsed, nanoTime.getAsLong());
                if (complete.isEmpty()) return List.of();
                Ipv4Packet ip = complete.get();
                return dispatcher.dispatch(ip.protocol(), ip.source(), ip.destination(), ip.payload(), Ipv4Codec.serialize(ip));
            }
            if (version == 6) {
                Ipv6Packet ip = Ipv6Codec.parse(rawPacket);
                IpAddress local = localAddresses.get(128);
                if (!ip.destination().equals(local)) return List.of();
                return dispatcher.dispatch(ip.nextHeader(), ip.source(), ip.destination(), ip.payload(), rawPacket);
            }
            return List.of();
        } catch (IllegalArgumentException malformed) {
            metrics.drop();
            LOG.fine(() -> "Dropping malformed packet: " + malformed.getMessage()); return List.of();
        }
    }

    private List<byte[]> processTcp(IpAddress source, IpAddress destination, byte[] payload, byte[] originalPacket) {
        final TcpSegment tcp = TcpCodec.parse(payload, source, destination);
        LOG.fine(() -> "RX TCP src=" + source + ":" + tcp.sourcePort() + " dst=" + destination +
                ":" + tcp.destinationPort() + " flags=" + TcpFlags.describe(tcp.flags()) + " seq=" + tcp.sequenceNumber());
        TcpConnectionKey key = new TcpConnectionKey(destination, tcp.destinationPort(), source, tcp.sourcePort());
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
            if (result.justEstablished() && listener != null && !listener.promote(key, connection)) {
                connections.remove(key);
                replies.clear();
                replies.add(resetFor(tcp));
            }
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
        LOG.fine(() -> "TX TCP flags=" + TcpFlags.describe(segment.flags()) + " seq=" + segment.sequenceNumber() +
                " ack=" + segment.acknowledgementNumber() + " len=" + segment.payload().length);
        return encodeIp(key.localAddress(), key.remoteAddress(), 6, tcp);
    }

    private List<byte[]> processUdp(IpAddress source, IpAddress destination, byte[] payload, byte[] originalPacket) {
        UdpDatagram datagram = UdpCodec.parse(payload, source, destination);
        Predicate<UdpReceivedDatagram> binding = udpBindings.get(datagram.destinationPort());
        if (binding != null) {
            if (!binding.test(new UdpReceivedDatagram(source, datagram.sourcePort(), datagram.payload()))) metrics.drop();
            return List.of();
        }
        if (destination.bitLength() == 32) {
            int quoteLength = Math.min(originalPacket.length, 28);
            IcmpMessage unreachable = new IcmpMessage(3, 3, 0, Arrays.copyOf(originalPacket, quoteLength));
            byte[] icmp = IcmpCodec.serializeV4(unreachable);
            return List.of(encodeIp(destination, source, 1, icmp));
        }
        int quoteLength = Math.min(originalPacket.length, 1232);
        IcmpMessage unreachable = new IcmpMessage(1, 4, 0, Arrays.copyOf(originalPacket, quoteLength));
        byte[] icmp = IcmpCodec.serializeV6(unreachable, destination, source);
        return List.of(encodeIp(destination, source, 58, icmp));
    }

    private List<byte[]> processIcmpV4(IpAddress source, IpAddress destination, byte[] payload, byte[] originalPacket) {
        IcmpMessage request = IcmpCodec.parseV4(payload);
        if (request.type() != 8 || request.code() != 0) return List.of();
        return List.of(encodeIp(destination, source, 1,
                IcmpCodec.serializeV4(new IcmpMessage(0, 0, request.restOfHeader(), request.payload()))));
    }

    private List<byte[]> processIcmpV6(IpAddress source, IpAddress destination, byte[] payload, byte[] originalPacket) {
        IcmpMessage request = IcmpCodec.parseV6(payload, source, destination);
        if (request.type() != 128 || request.code() != 0) return List.of();
        IcmpMessage reply = new IcmpMessage(129, 0, request.restOfHeader(), request.payload());
        return List.of(encodeIp(destination, source, 58, IcmpCodec.serializeV6(reply, destination, source)));
    }

    private byte[] encodeIp(IpAddress source, IpAddress destination, int protocol, byte[] payload) {
        if (source instanceof Ipv4Address source4 && destination instanceof Ipv4Address destination4) {
            return Ipv4Codec.serialize(new Ipv4Packet(0, nextIpIdentification.getAndIncrement() & 0xffff,
                    2, 0, 64, protocol, source4, destination4, new byte[0], payload));
        }
        if (source instanceof Ipv6Address source6 && destination instanceof Ipv6Address destination6) {
            return Ipv6Codec.serialize(new Ipv6Packet(0, 0, protocol, 64, source6, destination6, payload));
        }
        throw new IllegalArgumentException("mixed IP families");
    }

    public void bindUdp(int port, Consumer<UdpReceivedDatagram> receiver) {
        bindUdpBounded(port, datagram -> { receiver.accept(datagram); return true; });
    }
    public void bindUdpBounded(int port, Predicate<UdpReceivedDatagram> receiver) {
        if (port < 1 || port > 65_535 || receiver == null) throw new IllegalArgumentException("invalid UDP binding");
        if (udpBindings.putIfAbsent(port, receiver) != null) throw new IllegalStateException("UDP port already bound: " + port);
    }
    public void unbindUdp(int port) { udpBindings.remove(port); }
    public void queueDepth(int depth) { metrics.queueDepth(depth); }
    public void sendUdp(int sourcePort, IpAddress destination, int destinationPort, byte[] payload) {
        IpAddress source = localAddresses.get(destination.bitLength());
        if (source == null) throw new IllegalArgumentException("no local address for remote IP family");
        byte[] udp = UdpCodec.serialize(new UdpDatagram(sourcePort, destinationPort, payload), source, destination);
        asynchronousOutput.accept(encodeIp(source, destination, 17, udp));
    }
    private static TcpSegment resetFor(TcpSegment incoming) {
        if (incoming.has(TcpFlags.ACK)) return new TcpSegment(incoming.destinationPort(), incoming.sourcePort(),
                incoming.acknowledgementNumber(), 0, TcpFlags.RST, 0, 0, new byte[0], new byte[0]);
        long acknowledgement = SequenceNumber.add(incoming.sequenceNumber(), incoming.sequenceSpaceLength());
        return new TcpSegment(incoming.destinationPort(), incoming.sourcePort(), 0, acknowledgement,
                TcpFlags.RST | TcpFlags.ACK, 0, 0, new byte[0], new byte[0]);
    }

    public TcpConnectionTable connections() { return connections; }
    public void cancel(TcpConnection connection) { connections.remove(connection.key()); }
    public void transmit(TcpConnection connection, List<TcpSegment> segments) {
        for (TcpSegment segment : segments) asynchronousOutput.accept(encode(segment, connection.key()));
    }
    public void pollRetransmissions(long nowNanos) {
        for (TcpConnection connection : connections.snapshot()) {
            List<TcpSegment> due = connection.retransmissionsDue(nowNanos);
            if (!due.isEmpty()) { metrics.retransmissions(due.size()); metrics.rtoEvent(); }
            transmit(connection, due);
            if (connection.expireTimeWait(nowNanos) || connection.state() == TcpState.CLOSED) {
                connections.remove(connection.key());
                ListenerState listener = listeners.get(connection.key().localPort());
                if (listener != null) listener.remove(connection.key());
            }
        }
        metrics.activeConnections(connections.size());
    }
    public void accepted(TcpConnectionKey key) {
        ListenerState listener = listeners.get(key.localPort());
        if (listener != null) listener.accepted(key);
    }
    public int halfOpenCount(int port) { ListenerState listener = listeners.get(port); return listener == null ? 0 : listener.halfOpenCount(); }
    public int establishedBacklogCount(int port) { ListenerState listener = listeners.get(port); return listener == null ? 0 : listener.establishedCount(); }

    private static final class ListenerState {
        private final int backlog;
        private final boolean synCookies;
        private final Consumer<TcpConnection> callback;
        private final Set<TcpConnectionKey> halfOpen = new HashSet<>();
        private final Set<TcpConnectionKey> established = new HashSet<>();
        private ListenerState(int backlog, boolean synCookies, Consumer<TcpConnection> callback) {
            this.backlog = backlog; this.synCookies = synCookies; this.callback = callback;
        }
        synchronized boolean reserveHalfOpen(TcpConnectionKey key) {
            if (halfOpen.size() >= backlog) return false;
            return halfOpen.add(key);
        }
        synchronized boolean promote(TcpConnectionKey key, TcpConnection connection) {
            halfOpen.remove(key);
            if (established.size() >= backlog) return false;
            established.add(key); callback.accept(connection); return true;
        }
        synchronized boolean promoteCookie(TcpConnection connection) {
            if (established.size() >= backlog) return false;
            established.add(connection.key()); callback.accept(connection); return true;
        }
        synchronized void accepted(TcpConnectionKey key) { established.remove(key); }
        synchronized void remove(TcpConnectionKey key) { halfOpen.remove(key); established.remove(key); }
        synchronized int halfOpenCount() { return halfOpen.size(); }
        synchronized int establishedCount() { return established.size(); }
    }
}
