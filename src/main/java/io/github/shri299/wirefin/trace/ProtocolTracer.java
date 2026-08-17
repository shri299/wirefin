package io.github.shri299.wirefin.trace;

import io.github.shri299.wirefin.tcp.connection.TcpConnectionSnapshot;

/** Optional structured TCP trace sink. No event object is created when disabled. */
public interface ProtocolTracer extends AutoCloseable {
    boolean enabled();
    void onSegment(TcpSegmentTrace event);
    @Override void close();

    record TcpSegmentTrace(long timestampNanos, PacketCapture.Direction direction, int flags,
                           long sequenceNumber, long acknowledgementNumber, int payloadLength,
                           TcpConnectionSnapshot connection) {}

    static ProtocolTracer disabled() { return Disabled.INSTANCE; }

    enum Disabled implements ProtocolTracer {
        INSTANCE;
        @Override public boolean enabled() { return false; }
        @Override public void onSegment(TcpSegmentTrace event) {}
        @Override public void close() {}
    }
}
