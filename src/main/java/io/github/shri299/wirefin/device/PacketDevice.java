package io.github.shri299.wirefin.device;

import java.io.Closeable;
import java.io.IOException;

/** A source and sink of complete layer-3 packets. */
public interface PacketDevice extends Closeable {
    byte[] read() throws IOException;
    void write(byte[] packet) throws IOException;

    /** Receives up to batch capacity. Blocking behavior is backend-specific. */
    default int receive(PacketBatch batch) throws IOException {
        batch.clear(); batch.add(read()); return 1;
    }

    /** Transmits every packet in order and returns the number accepted. */
    default int transmit(PacketBatch batch) throws IOException {
        for (int i = 0; i < batch.size(); i++) write(batch.get(i));
        return batch.size();
    }
}
