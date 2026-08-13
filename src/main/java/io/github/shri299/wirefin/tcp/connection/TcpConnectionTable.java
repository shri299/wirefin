package io.github.shri299.wirefin.tcp.connection;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class TcpConnectionTable {
    private final ConcurrentHashMap<TcpConnectionKey, TcpConnection> connections = new ConcurrentHashMap<>();
    public Optional<TcpConnection> find(TcpConnectionKey key) { return Optional.ofNullable(connections.get(key)); }
    public TcpConnection add(TcpConnection connection) {
        TcpConnection prior = connections.putIfAbsent(connection.key(), connection);
        if (prior != null) throw new IllegalStateException("connection already exists: " + connection.key());
        return connection;
    }
    public void remove(TcpConnectionKey key) { connections.remove(key); }
    public Collection<TcpConnection> snapshot() { return List.copyOf(connections.values()); }
    public int size() { return connections.size(); }
}
