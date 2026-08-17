package io.github.shri299.wirefin.udp;

import java.util.Arrays;
import java.nio.ByteBuffer;

public record UdpDatagram(int sourcePort, int destinationPort, byte[] payload) {
    public UdpDatagram {
        if (sourcePort < 0 || sourcePort > 65535 || destinationPort < 0 || destinationPort > 65535 || payload == null)
            throw new IllegalArgumentException("invalid UDP datagram");
        if (payload.length > 65_527) throw new IllegalArgumentException("UDP payload too long");
        payload = Arrays.copyOf(payload, payload.length);
    }
    @Override public byte[] payload() { return Arrays.copyOf(payload, payload.length); }
    int payloadLength() { return payload.length; }
    void writePayloadTo(ByteBuffer destination) { destination.put(payload); }
    @Override public boolean equals(Object other) { return other instanceof UdpDatagram that && sourcePort == that.sourcePort &&
            destinationPort == that.destinationPort && Arrays.equals(payload, that.payload); }
    @Override public int hashCode() { return 31 * java.util.Objects.hash(sourcePort, destinationPort) + Arrays.hashCode(payload); }
}
