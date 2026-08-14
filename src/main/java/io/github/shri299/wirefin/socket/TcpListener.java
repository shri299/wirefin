package io.github.shri299.wirefin.socket;

import io.github.shri299.wirefin.runtime.PacketProcessor;
import io.github.shri299.wirefin.tcp.connection.TcpConnection;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public final class TcpListener {
    private final BlockingQueue<TcpConnection> accepted = new LinkedBlockingQueue<>();
    private final PacketProcessor processor;

    public TcpListener(PacketProcessor processor, int port) {
        this(processor, port, 128, false);
    }

    public TcpListener(PacketProcessor processor, int port, int backlog, boolean synCookies) {
        this.processor = processor;
        processor.listen(port, backlog, synCookies, accepted::offer);
    }

    public TcpSocket accept() throws InterruptedException {
        TcpConnection connection = accepted.take();
        processor.accepted(connection.key());
        return new TcpSocket(connection, processor);
    }
}
