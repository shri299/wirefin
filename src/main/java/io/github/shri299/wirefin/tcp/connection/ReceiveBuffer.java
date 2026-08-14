package io.github.shri299.wirefin.tcp.connection;

import io.github.shri299.wirefin.tcp.TcpOptions;
import io.github.shri299.wirefin.tcp.reliability.SequenceNumber;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Bounded TCP byte-stream reassembler. Pending bytes are keyed by distance from RCV.NXT. */
final class ReceiveBuffer {
    private final int capacity;
    private final byte[] readable;
    private final TreeMap<Integer, Byte> pending = new TreeMap<>();
    private int head;
    private int readableBytes;
    private long receiveNext;
    private boolean eof;

    ReceiveBuffer(long receiveNext, int capacity) {
        if (capacity < 1 || capacity > 16 * 1024 * 1024)
            throw new IllegalArgumentException("receive capacity outside Wirefin bounds");
        this.receiveNext = receiveNext;
        this.capacity = capacity;
        this.readable = new byte[capacity];
    }

    synchronized int accept(long sequence, byte[] payload) {
        int before = readableBytes;
        int window = advertisedWindow();
        for (int i = 0; i < payload.length; i++) {
            long byteSequence = SequenceNumber.add(sequence, i);
            long distance = SequenceNumber.distance(receiveNext, byteSequence);
            if (distance > Integer.MAX_VALUE) continue; // already cumulatively received
            int offset = (int) distance;
            if (pending.containsKey(offset)) continue;
            if (offset >= window || totalBuffered() >= capacity) continue;
            pending.put(offset, payload[i]);
        }
        drainContiguous();
        return readableBytes - before;
    }

    private void drainContiguous() {
        int count = 0;
        while (pending.containsKey(count)) count++;
        if (count == 0) return;
        for (int i = 0; i < count; i++) appendReadable(pending.remove(i));
        TreeMap<Integer, Byte> shifted = new TreeMap<>();
        for (Map.Entry<Integer, Byte> entry : pending.entrySet()) shifted.put(entry.getKey() - count, entry.getValue());
        pending.clear();
        pending.putAll(shifted);
        receiveNext = SequenceNumber.add(receiveNext, count);
        notifyAll();
    }

    private void appendReadable(byte value) {
        readable[(head + readableBytes) % capacity] = value;
        readableBytes++;
    }

    synchronized int read(byte[] destination, int offset, int length) throws InterruptedException {
        if (offset < 0 || length < 0 || offset + length > destination.length) throw new IndexOutOfBoundsException();
        if (length == 0) return 0;
        while (readableBytes == 0 && !eof) wait();
        if (readableBytes == 0) return -1;
        int count = Math.min(length, readableBytes);
        for (int i = 0; i < count; i++) destination[offset + i] = readable[(head + i) % capacity];
        head = (head + count) % capacity;
        readableBytes -= count;
        return count;
    }

    synchronized void markEof() { eof = true; notifyAll(); }
    synchronized void advanceControlSequence() { receiveNext = SequenceNumber.add(receiveNext, 1); }
    synchronized void resetReceiveNext(long value) {
        if (totalBuffered() != 0) throw new IllegalStateException("cannot reset a populated receive buffer");
        receiveNext = value;
    }
    synchronized long receiveNext() { return receiveNext; }
    synchronized int advertisedWindow() { return capacity - totalBuffered(); }
    synchronized int readableBytes() { return readableBytes; }
    synchronized int outOfOrderBytes() { return pending.size(); }
    synchronized List<TcpOptions.SackBlock> sackBlocks() {
        List<TcpOptions.SackBlock> result = new ArrayList<>();
        Integer start = null, previous = null;
        for (Integer offset : pending.keySet()) {
            if (start == null) { start = previous = offset; continue; }
            if (offset != previous + 1) {
                result.add(new TcpOptions.SackBlock(SequenceNumber.add(receiveNext, start),
                        SequenceNumber.add(receiveNext, previous + 1L)));
                start = offset;
            }
            previous = offset;
        }
        if (start != null) result.add(new TcpOptions.SackBlock(SequenceNumber.add(receiveNext, start),
                SequenceNumber.add(receiveNext, previous + 1L)));
        result.sort((left, right) -> Long.compareUnsigned(
                SequenceNumber.distance(receiveNext, right.leftEdge()),
                SequenceNumber.distance(receiveNext, left.leftEdge())));
        return result.size() <= 4 ? List.copyOf(result) : List.copyOf(result.subList(0, 4));
    }
    private int totalBuffered() { return readableBytes + pending.size(); }
}
