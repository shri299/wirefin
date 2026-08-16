package io.github.shri299.wirefin.socket;

import io.github.shri299.wirefin.runtime.PacketProcessor;
import io.github.shri299.wirefin.tcp.connection.TcpConnection;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public final class TcpListener {
    private final BlockingQueue<TcpConnection> accepted;
    private final PacketProcessor processor;

    public TcpListener(PacketProcessor processor, int port) {
        this(processor, port, 128, false);
    }

    public TcpListener(PacketProcessor processor, int port, int backlog, boolean synCookies) {
        this.processor = processor; accepted = new LinkedBlockingQueue<>(backlog);
        processor.listen(port, backlog, synCookies, connection -> { accepted.offer(connection); processor.queueDepth(accepted.size()); });
    }

    public TcpSocket accept() throws InterruptedException {
        TcpConnection connection = accepted.take();
        processor.queueDepth(accepted.size());
        processor.accepted(connection.key());
        return new TcpSocket(connection, processor);
    }
}
