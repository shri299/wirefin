package io.github.shri299.wirefin.trace;

import java.io.IOException;

/** Optional raw-IP packet capture; disabled capture is allocation-free. */
public interface PacketCapture extends AutoCloseable {
    enum Direction { RX, TX }
    boolean enabled();
    void record(long timestampNanos, Direction direction, byte[] packet) throws IOException;
    @Override void close() throws IOException;

    static PacketCapture disabled() { return Disabled.INSTANCE; }

    enum Disabled implements PacketCapture {
        INSTANCE;
        @Override public boolean enabled() { return false; }
        @Override public void record(long timestampNanos, Direction direction, byte[] packet) {}
        @Override public void close() {}
    }
}
