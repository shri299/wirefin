package io.github.shri299.wirefin.ip;

/** Immutable network-layer address shared by transport protocols. */
public interface IpAddress {
    byte[] bytes();
    int bitLength();
    int unsignedByte(int index);
}
