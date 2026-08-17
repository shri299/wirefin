package io.github.shri299.wirefin.socket;

import io.github.shri299.wirefin.ip.IpAddress;
import io.github.shri299.wirefin.runtime.PacketProcessor;
import java.time.Duration;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Datagram-oriented socket facade; messages retain source and boundary information. */
public final class UdpSocket implements AutoCloseable {
    private final PacketProcessor processor; private final int localPort;
    private final LinkedBlockingQueue<UdpReceivedDatagram> received;
    private volatile boolean closed;
    public UdpSocket(PacketProcessor processor, int localPort) {
        this(processor, localPort, 1024);
    }
    public UdpSocket(PacketProcessor processor, int localPort, int queueCapacity) {
        if (queueCapacity < 1) throw new IllegalArgumentException("queue capacity must be positive");
        this.processor = processor; this.localPort = localPort; received = new LinkedBlockingQueue<>(queueCapacity);
        processor.bindUdpBounded(localPort, datagram -> {
            boolean accepted = received.offer(datagram); processor.queueDepth(received.size()); return accepted;
        });
    }
    public int localPort() { return localPort; }
    public void sendTo(IpAddress destination, int port, byte[] payload) {
        if (closed) throw new IllegalStateException("UDP socket closed");
        processor.sendUdp(localPort, destination, port, payload);
    }
    public UdpReceivedDatagram receive(Duration timeout) throws InterruptedException {
        UdpReceivedDatagram datagram = received.poll(timeout.toNanos(), TimeUnit.NANOSECONDS);
        if (datagram == null) throw new IllegalStateException("UDP receive timed out");
        processor.queueDepth(received.size());
        return datagram;
    }
    @Override public void close() { if (!closed) { closed = true; processor.unbindUdp(localPort); } }
}
