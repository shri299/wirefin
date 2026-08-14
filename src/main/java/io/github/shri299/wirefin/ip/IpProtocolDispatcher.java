package io.github.shri299.wirefin.ip;

import java.util.*;

/** Small explicit next-header/protocol registry shared by IPv4 and IPv6. */
public final class IpProtocolDispatcher {
    private final Map<Integer, Handler> handlers = new HashMap<>();
    public void register(int protocol, Handler handler) {
        if (protocol < 0 || protocol > 255 || handler == null) throw new IllegalArgumentException("invalid protocol handler");
        handlers.put(protocol, handler);
    }
    public List<byte[]> dispatch(int protocol, IpAddress source, IpAddress destination, byte[] payload, byte[] originalPacket) {
        Handler handler = handlers.get(protocol);
        return handler == null ? List.of() : handler.handle(source, destination, payload, originalPacket);
    }
    @FunctionalInterface public interface Handler {
        List<byte[]> handle(IpAddress source, IpAddress destination, byte[] payload, byte[] originalPacket);
    }
}
