package io.github.shri299.wirefin.trace;

import io.github.shri299.wirefin.tcp.connection.TcpConnectionSnapshot;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** Dependency-free JSON Lines TCP trace intended for diagnostics and demos. */
public final class JsonLineProtocolTracer implements ProtocolTracer {
    private final Writer writer;
    private boolean closed;

    public JsonLineProtocolTracer(Path path) throws IOException {
        if (path == null) throw new IllegalArgumentException("trace path required");
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8);
    }

    JsonLineProtocolTracer(Writer writer) { this.writer = writer; }

    @Override public boolean enabled() { return true; }

    @Override public synchronized void onSegment(TcpSegmentTrace event) {
        if (closed) throw new IllegalStateException("protocol trace closed");
        TcpConnectionSnapshot connection = event.connection();
        try {
            writer.write("{\"timestamp_nanos\":" + event.timestampNanos() +
                    ",\"direction\":\"" + event.direction() + "\",\"connection_id\":" + connection.id() +
                    ",\"local\":\"" + escape(connection.key().localAddress() + ":" + connection.key().localPort()) +
                    "\",\"remote\":\"" + escape(connection.key().remoteAddress() + ":" + connection.key().remotePort()) +
                    "\",\"state\":\"" + connection.state() + "\",\"flags\":" + event.flags() +
                    ",\"seq\":" + event.sequenceNumber() + ",\"ack\":" + event.acknowledgementNumber() +
                    ",\"payload_bytes\":" + event.payloadLength() + ",\"snd_una\":" + connection.sendUnacknowledged() +
                    ",\"snd_nxt\":" + connection.sendNext() + ",\"rcv_nxt\":" + connection.receiveNext() +
                    ",\"cwnd\":" + connection.congestionWindow() + ",\"ssthresh\":" + connection.slowStartThreshold() +
                    ",\"peer_rwnd\":" + connection.peerReceiveWindow() + ",\"srtt_nanos\":" + connection.smoothedRttNanos() +
                    ",\"rttvar_nanos\":" + connection.rttVariationNanos() + ",\"rto_nanos\":" + connection.rtoNanos() +
                    ",\"retransmissions\":" + connection.retransmissions() + "}\n");
            writer.flush();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        try { writer.close(); } catch (IOException failure) { throw new UncheckedIOException(failure); }
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
