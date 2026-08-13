package io.github.shri299.wirefin.runtime;

import io.github.shri299.wirefin.device.PacketDevice;
import io.github.shri299.wirefin.ipv4.Ipv4Address;
import io.github.shri299.wirefin.socket.TcpListener;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Drives raw packets between a PacketDevice and the pure protocol processor. */
public final class TcpStack implements AutoCloseable {
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

    public void run() throws IOException {
        if (!running.compareAndSet(false, true)) throw new IllegalStateException("stack already running");
        timers.scheduleAtFixedRate(() -> processor.pollRetransmissions(System.nanoTime()), 100, 100, TimeUnit.MILLISECONDS);
        try {
            while (running.get()) {
                byte[] packet = device.read();
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
    }
}
