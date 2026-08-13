package io.github.shri299.wirefin.socket;

import io.github.shri299.wirefin.runtime.PacketProcessor;
import io.github.shri299.wirefin.tcp.connection.TcpConnection;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public final class TcpListener {
    private final BlockingQueue<TcpConnection> accepted = new LinkedBlockingQueue<>();
    private final PacketProcessor processor;

    public TcpListener(PacketProcessor processor, int port) {
        this.processor = processor;
        processor.listen(port, accepted::offer);
    }

    public TcpSocket accept() throws InterruptedException { return new TcpSocket(accepted.take(), processor); }
}
