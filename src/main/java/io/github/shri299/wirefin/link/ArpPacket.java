package io.github.shri299.wirefin.link;

import io.github.shri299.wirefin.ipv4.Ipv4Address;

public record ArpPacket(int operation, MacAddress senderMac, Ipv4Address senderIp,
                        MacAddress targetMac, Ipv4Address targetIp) {
    public static final int REQUEST = 1, REPLY = 2;
    public ArpPacket { if (operation != REQUEST && operation != REPLY || senderMac == null || senderIp == null || targetMac == null || targetIp == null) throw new IllegalArgumentException("invalid ARP packet"); }
}
