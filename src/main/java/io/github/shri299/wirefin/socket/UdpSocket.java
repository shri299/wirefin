package io.github.shri299.wirefin.socket;

import io.github.shri299.wirefin.ip.IpAddress;
import io.github.shri299.wirefin.runtime.PacketProcessor;
import java.time.Duration;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Datagram-oriented socket facade; messages retain source and boundary information. */
public final class UdpSocket implements AutoCloseable {
    private final PacketProcessor processor; private final int localPort;
    private final LinkedBlockingQueue<UdpReceivedDatagram> received = new LinkedBlockingQueue<>();
    private volatile boolean closed;
    public UdpSocket(PacketProcessor processor, int localPort) {
        this.processor = processor; this.localPort = localPort; processor.bindUdp(localPort, received::add);
    }
    public int localPort() { return localPort; }
    public void sendTo(IpAddress destination, int port, byte[] payload) {
        if (closed) throw new IllegalStateException("UDP socket closed");
        processor.sendUdp(localPort, destination, port, payload);
    }
    public UdpReceivedDatagram receive(Duration timeout) throws InterruptedException {
        UdpReceivedDatagram datagram = received.poll(timeout.toNanos(), TimeUnit.NANOSECONDS);
        if (datagram == null) throw new IllegalStateException("UDP receive timed out");
        return datagram;
    }
    @Override public void close() { if (!closed) { closed = true; processor.unbindUdp(localPort); } }
}
