package io.github.shri299.wirefin.runtime;

import io.github.shri299.wirefin.device.PacketDevice;
import io.github.shri299.wirefin.ip.IpAddress;
import io.github.shri299.wirefin.socket.*;
import java.io.IOException;
import java.time.Duration;
import java.util.Collection;

/** Coherent dual-stack application facade for TCP streams and UDP datagrams. */
public final class NetworkStack implements AutoCloseable {
    private final TcpStack runtime;
    public NetworkStack(PacketDevice device, Collection<? extends IpAddress> localAddresses) {
        runtime = new TcpStack(device, localAddresses);
    }
    public TcpListener listenTcp(int port) { return runtime.listen(port); }
    public TcpListener listenTcp(int port, int backlog, boolean synCookies) { return runtime.listen(port, backlog, synCookies); }
    public TcpSocket connectTcp(IpAddress remote, int port, Duration timeout) throws InterruptedException {
        return runtime.connect(remote, port, timeout);
    }
    public UdpSocket bindUdp(int port) { return runtime.bindUdp(port); }
    public void run() throws IOException { runtime.run(); }
    @Override public void close() throws IOException { runtime.close(); }
}
