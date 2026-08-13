package io.github.shri299.wirefin.runtime;

import io.github.shri299.wirefin.ipv4.Ipv4Address;
import io.github.shri299.wirefin.ipv4.Ipv4Codec;
import io.github.shri299.wirefin.ipv4.Ipv4Packet;
import io.github.shri299.wirefin.tcp.TcpCodec;
import io.github.shri299.wirefin.tcp.TcpFlags;
import io.github.shri299.wirefin.tcp.TcpSegment;
import io.github.shri299.wirefin.tcp.connection.TcpConnection;
import io.github.shri299.wirefin.tcp.connection.TcpConnectionKey;
import io.github.shri299.wirefin.tcp.connection.TcpConnectionTable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import java.util.function.Consumer;
import java.util.logging.Logger;

/** Pure packet-in/packet-out protocol core; it has no dependency on TUN or kernel sockets. */
public final class PacketProcessor {
    private static final Logger LOG = Logger.getLogger(PacketProcessor.class.getName());
    private final Ipv4Address localAddress;
    private final ConcurrentHashMap<Integer, Consumer<TcpConnection>> listeners = new ConcurrentHashMap<>();
    private final TcpConnectionTable connections = new TcpConnectionTable();
    private final LongSupplier isnSource;
    private final Consumer<byte[]> asynchronousOutput;
    private final AtomicInteger nextIpIdentification = new AtomicInteger();
    private final LongSupplier nanoTime;
    private final TcpConnection.Config connectionConfig;

    public PacketProcessor(Ipv4Address localAddress) {
        this(localAddress, () -> ThreadLocalRandom.current().nextLong(1L << 32), ignored -> { });
    }

    public PacketProcessor(Ipv4Address localAddress, LongSupplier isnSource) {
        this(localAddress, isnSource, ignored -> { });
    }

    public PacketProcessor(Ipv4Address localAddress, LongSupplier isnSource, Consumer<byte[]> asynchronousOutput) {
        this(localAddress, isnSource, asynchronousOutput, System::nanoTime, TcpConnection.Config.defaults());
    }

    public PacketProcessor(Ipv4Address localAddress, LongSupplier isnSource, Consumer<byte[]> asynchronousOutput,
                           LongSupplier nanoTime, TcpConnection.Config connectionConfig) {
        this.localAddress = localAddress;
        this.isnSource = isnSource;
        this.asynchronousOutput = asynchronousOutput;
        this.nanoTime = nanoTime;
        this.connectionConfig = connectionConfig;
    }

    public void listen(int port) {
        if (port < 1 || port > 65535) throw new IllegalArgumentException("invalid listen port");
        listen(port, ignored -> { });
    }

    public void listen(int port, Consumer<TcpConnection> onEstablished) {
        if (port < 1 || port > 65535) throw new IllegalArgumentException("invalid listen port");
        if (listeners.putIfAbsent(port, onEstablished) != null) throw new IllegalStateException("port already listening: " + port);
    }

    public List<byte[]> process(byte[] rawPacket) {
        final Ipv4Packet ip;
        final TcpSegment tcp;
        try {
            ip = Ipv4Codec.parse(rawPacket);
            if (!ip.destination().equals(localAddress) || ip.protocol() != Ipv4Packet.PROTOCOL_TCP || ip.isFragmented()) return List.of();
            tcp = TcpCodec.parse(ip.payload(), ip.source(), ip.destination());
        } catch (IllegalArgumentException malformed) {
            LOG.fine(() -> "Dropping malformed packet: " + malformed.getMessage());
            return List.of();
        }
        LOG.fine(() -> "RX TCP src=" + ip.source() + ":" + tcp.sourcePort() + " dst=" + ip.destination() +
                ":" + tcp.destinationPort() + " flags=" + TcpFlags.describe(tcp.flags()) + " seq=" + tcp.sequenceNumber());
        TcpConnectionKey key = new TcpConnectionKey(localAddress, tcp.destinationPort(), ip.source(), tcp.sourcePort());
        TcpConnection connection = connections.find(key).orElse(null);
        List<TcpSegment> replies = new ArrayList<>();
        if (connection == null) {
            if (listeners.containsKey(tcp.destinationPort()) && tcp.has(TcpFlags.SYN) && !tcp.has(TcpFlags.ACK)) {
                connection = connections.add(TcpConnection.passiveOpen(key, isnSource.getAsLong(), tcp,
                        nanoTime.getAsLong(), connectionConfig));
                replies.add(connection.synAck());
            } else if (!tcp.has(TcpFlags.RST)) {
                replies.add(resetFor(tcp));
            }
        } else {
            TcpConnection.ProcessingResult result = connection.receive(tcp, nanoTime.getAsLong());
            replies.addAll(result.outbound());
            if (result.justEstablished()) listeners.getOrDefault(key.localPort(), ignored -> { }).accept(connection);
            if (result.closed()) connections.remove(key);
        }
        return replies.stream().map(reply -> encode(reply, key)).toList();
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
        if (incoming.has(TcpFlags.ACK))
            return new TcpSegment(incoming.destinationPort(), incoming.sourcePort(), incoming.acknowledgementNumber(),
                    0, TcpFlags.RST, 0, 0, new byte[0], new byte[0]);
        long acknowledgement = io.github.shri299.wirefin.tcp.reliability.SequenceNumber.add(
                incoming.sequenceNumber(), incoming.sequenceSpaceLength());
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
            if (connection.expireTimeWait(nowNanos)) connections.remove(connection.key());
        }
    }
}
