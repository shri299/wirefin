package io.github.shri299.wirefin.device;

import java.io.Closeable;
import java.io.IOException;

/** A source and sink of complete layer-3 packets. */
public interface PacketDevice extends Closeable {
    byte[] read() throws IOException;
    void write(byte[] packet) throws IOException;
}
