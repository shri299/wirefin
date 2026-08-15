package io.github.shri299.wirefin.socket;

import io.github.shri299.wirefin.ip.IpAddress;
import java.util.Arrays;

public record UdpReceivedDatagram(IpAddress sourceAddress, int sourcePort, byte[] payload) {
    public UdpReceivedDatagram { payload = Arrays.copyOf(payload, payload.length); }
    @Override public byte[] payload() { return Arrays.copyOf(payload, payload.length); }
}
