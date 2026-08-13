package io.github.shri299.wirefin.socket;

import io.github.shri299.wirefin.runtime.PacketProcessor;
import io.github.shri299.wirefin.tcp.connection.TcpConnection;
import java.util.Arrays;

/** Blocking, stream-oriented application view backed solely by Wirefin TCP. */
public final class TcpSocket implements AutoCloseable {
    private final TcpConnection connection;
    private final PacketProcessor processor;

    TcpSocket(TcpConnection connection, PacketProcessor processor) {
        this.connection = connection;
        this.processor = processor;
    }

    /** Returns the next contiguous run of bytes, or null after peer FIN. */
    public byte[] read() throws InterruptedException { return connection.read(); }

    public void write(byte[] bytes) {
        processor.transmit(connection, connection.send(Arrays.copyOf(bytes, bytes.length), System.nanoTime()));
    }

    @Override public void close() {
        processor.transmit(connection, connection.close(System.nanoTime()));
    }

    public TcpConnection controlBlock() { return connection; }
}
