package io.github.shri299.wirefin.tcp.connection;

import io.github.shri299.wirefin.ipv4.Ipv4Address;

/** Local/remote four-tuple, normalized from the stack's perspective. */
public record TcpConnectionKey(Ipv4Address localAddress, int localPort,
                               Ipv4Address remoteAddress, int remotePort) {
    public TcpConnectionKey {
        if (localAddress == null || remoteAddress == null || localPort < 0 || localPort > 65535 ||
                remotePort < 0 || remotePort > 65535) throw new IllegalArgumentException("invalid TCP four-tuple");
    }
}
