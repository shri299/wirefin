package io.github.shri299.wirefin.tcp.connection;

import io.github.shri299.wirefin.ip.IpAddress;

/** Local/remote four-tuple, normalized from the stack's perspective. */
public record TcpConnectionKey(IpAddress localAddress, int localPort,
                               IpAddress remoteAddress, int remotePort) {
    public TcpConnectionKey {
        if (localAddress == null || remoteAddress == null || localPort < 0 || localPort > 65535 ||
                remotePort < 0 || remotePort > 65535 || localAddress.bitLength() != remoteAddress.bitLength())
            throw new IllegalArgumentException("invalid TCP four-tuple");
    }
}
