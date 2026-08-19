package io.github.shri299.wirefin.tcp.connection;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class TcpConnectionTable {
    private final ConcurrentHashMap<TcpConnectionKey, TcpConnection> connections = new ConcurrentHashMap<>();
    private final int capacity;
    public TcpConnectionTable() { this(65_536); }
    public TcpConnectionTable(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("connection capacity must be positive");
        this.capacity = capacity;
    }
    public Optional<TcpConnection> find(TcpConnectionKey key) { return Optional.ofNullable(connections.get(key)); }
    public synchronized TcpConnection add(TcpConnection connection) {
        if (connections.size() >= capacity) throw new CapacityExceededException(capacity);
        TcpConnection prior = connections.putIfAbsent(connection.key(), connection);
        if (prior != null) throw new IllegalStateException("connection already exists: " + connection.key());
        return connection;
    }
    public synchronized void remove(TcpConnectionKey key) { connections.remove(key); }
    public Collection<TcpConnection> snapshot() { return List.copyOf(connections.values()); }
    public int size() { return connections.size(); }
    public int capacity() { return capacity; }

    public static final class CapacityExceededException extends IllegalStateException {
        public CapacityExceededException(int capacity) { super("TCP connection table full (capacity=" + capacity + ")"); }
    }
}
