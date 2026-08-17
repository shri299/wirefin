package io.github.shri299.wirefin.runtime;

import io.github.shri299.wirefin.device.PacketDevice;
import io.github.shri299.wirefin.device.PacketBatch;
import io.github.shri299.wirefin.ip.IpAddress;
import io.github.shri299.wirefin.ipv4.Ipv4Address;
import io.github.shri299.wirefin.socket.TcpListener;
import io.github.shri299.wirefin.socket.TcpSocket;
import io.github.shri299.wirefin.socket.UdpSocket;
import io.github.shri299.wirefin.metrics.NetworkMetrics;
import io.github.shri299.wirefin.tcp.connection.TcpConnectionSnapshot;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Drives raw packets between a PacketDevice and the pure protocol processor. */
public final class TcpStack implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(TcpStack.class.getName());
    private final PacketDevice device;
    private final PacketProcessor processor;
    private final ScheduledExecutorService timers = Executors.newSingleThreadScheduledExecutor();
    private final AtomicBoolean running = new AtomicBoolean();
    private final NetworkMetrics metrics = new NetworkMetrics();
    private final int batchSize;

    public TcpStack(PacketDevice device, Ipv4Address localAddress) {
        this(device, java.util.List.of(localAddress));
    }

    public TcpStack(PacketDevice device, java.util.Collection<? extends IpAddress> localAddresses) {
        this(device, localAddresses, 32);
    }

    public TcpStack(PacketDevice device, java.util.Collection<? extends IpAddress> localAddresses, int batchSize) {
        if (batchSize < 1 || batchSize > 4096) throw new IllegalArgumentException("invalid batch size");
        this.device = device;
        this.batchSize = batchSize;
        this.processor = new PacketProcessor(localAddresses,
                () -> java.util.concurrent.ThreadLocalRandom.current().nextLong(1L << 32), this::writeUnchecked,
                System::nanoTime, io.github.shri299.wirefin.tcp.connection.TcpConnection.Config.defaults(), metrics);
    }

    public TcpListener listen(int port) { return new TcpListener(processor, port); }
    public TcpListener listen(int port, int backlog, boolean synCookies) {
        return new TcpListener(processor, port, backlog, synCookies);
    }

    /** Active-open client API. The packet loop must already be running. */
    public TcpSocket connect(IpAddress remoteAddress, int remotePort, java.time.Duration timeout)
            throws InterruptedException {
        var connection = processor.connect(remoteAddress, remotePort);
        try {
            connection.awaitEstablished(timeout);
            return new TcpSocket(connection, processor);
        } catch (InterruptedException | RuntimeException failure) {
            processor.cancel(connection);
            throw failure;
        }
    }

    public UdpSocket bindUdp(int port) { return new UdpSocket(processor, port); }

    public void run() throws IOException {
        if (!running.compareAndSet(false, true)) throw new IllegalStateException("stack already running");
        timers.scheduleAtFixedRate(() -> {
            try { processor.pollRetransmissions(System.nanoTime()); }
            catch (RuntimeException failure) { LOG.log(Level.SEVERE, "TCP timer processing failed", failure); }
        }, 100, 100, TimeUnit.MILLISECONDS);
        PacketBatch incoming = new PacketBatch(batchSize);
        PacketBatch outgoing = new PacketBatch(batchSize);
        try {
            while (running.get()) {
                int received;
                try { received = device.receive(incoming); }
                catch (IOException closed) { if (!running.get()) break; else throw closed; }
                if (received < 0 || received > incoming.size()) throw new IOException("invalid device receive count");
                metrics.batch(received);
                for (int i = 0; i < received; i++) {
                    byte[] packet = incoming.get(i); metrics.received(packet.length);
                    for (byte[] response : processor.process(packet)) {
                        if (!outgoing.add(response)) { writeBatch(outgoing); outgoing.clear(); outgoing.add(response); }
                    }
                }
                if (outgoing.size() > 0) { writeBatch(outgoing); outgoing.clear(); }
            }
        } finally {
            running.set(false);
        }
    }

    private void writeUnchecked(byte[] packet) {
        try { writePacket(packet); }
        catch (IOException e) { throw new UncheckedIOException(e); }
    }

    private synchronized void writePacket(byte[] packet) throws IOException { device.write(packet); metrics.transmitted(packet.length); }

    private synchronized void writeBatch(PacketBatch batch) throws IOException {
        int accepted = device.transmit(batch);
        if (accepted < 0 || accepted > batch.size()) throw new IOException("invalid device transmit count");
        for (int i = 0; i < accepted; i++) metrics.transmitted(batch.get(i).length);
        for (int i = accepted; i < batch.size(); i++) metrics.drop();
    }

    public NetworkMetrics.Snapshot metrics() { return metrics.snapshot(); }
    /** Educational equivalent of a small, read-only subset of {@code ss -ti}. */
    public java.util.List<TcpConnectionSnapshot> connections() { return processor.connectionSnapshots(); }

    @Override public void close() throws IOException {
        running.set(false);
        timers.shutdownNow();
        device.close();
        try { timers.awaitTermination(2, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
