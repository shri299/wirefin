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
    public byte[] read() throws InterruptedException {
        byte[] bytes = connection.read();
        if (bytes != null) processor.transmit(connection, java.util.List.of(connection.ack()));
        return bytes;
    }

    public int read(byte[] destination) throws InterruptedException { return read(destination, 0, destination.length); }

    public int read(byte[] destination, int offset, int length) throws InterruptedException {
        int count = connection.read(destination, offset, length);
        if (count > 0) processor.transmit(connection, java.util.List.of(connection.ack()));
        return count;
    }

    public void write(byte[] bytes) {
        processor.transmit(connection, connection.send(Arrays.copyOf(bytes, bytes.length), System.nanoTime()));
    }

    @Override public void close() {
        processor.transmit(connection, connection.close(System.nanoTime()));
    }

    public TcpConnection controlBlock() { return connection; }
}
