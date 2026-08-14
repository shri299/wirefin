package io.github.shri299.wirefin.runtime;

import io.github.shri299.wirefin.device.PacketDevice;
import io.github.shri299.wirefin.ipv4.Ipv4Address;
import io.github.shri299.wirefin.socket.TcpListener;
import io.github.shri299.wirefin.socket.TcpSocket;

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

    public TcpStack(PacketDevice device, Ipv4Address localAddress) {
        this.device = device;
        this.processor = new PacketProcessor(localAddress,
                () -> java.util.concurrent.ThreadLocalRandom.current().nextLong(1L << 32), this::writeUnchecked);
    }

    public TcpListener listen(int port) { return new TcpListener(processor, port); }
    public TcpListener listen(int port, int backlog, boolean synCookies) {
        return new TcpListener(processor, port, backlog, synCookies);
    }

    /** Active-open client API. The packet loop must already be running. */
    public TcpSocket connect(Ipv4Address remoteAddress, int remotePort, java.time.Duration timeout)
            throws InterruptedException {
        var connection = processor.connect(remoteAddress, remotePort);
        connection.awaitEstablished(timeout);
        return new TcpSocket(connection, processor);
    }

    public void run() throws IOException {
        if (!running.compareAndSet(false, true)) throw new IllegalStateException("stack already running");
        timers.scheduleAtFixedRate(() -> {
            try { processor.pollRetransmissions(System.nanoTime()); }
            catch (RuntimeException failure) { LOG.log(Level.SEVERE, "TCP timer processing failed", failure); }
        }, 100, 100, TimeUnit.MILLISECONDS);
        try {
            while (running.get()) {
                byte[] packet;
                try { packet = device.read(); }
                catch (IOException closed) { if (!running.get()) break; else throw closed; }
                for (byte[] response : processor.process(packet)) writePacket(response);
            }
        } finally {
            running.set(false);
        }
    }

    private void writeUnchecked(byte[] packet) {
        try { writePacket(packet); }
        catch (IOException e) { throw new UncheckedIOException(e); }
    }

    private synchronized void writePacket(byte[] packet) throws IOException { device.write(packet); }

    @Override public void close() throws IOException {
        running.set(false);
        timers.shutdownNow();
        device.close();
        try { timers.awaitTermination(2, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
