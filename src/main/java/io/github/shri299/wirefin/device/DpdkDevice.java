package io.github.shri299.wirefin.device;

import io.github.shri299.wirefin.link.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Optional Linux/DPDK backend. JNI polls mbuf bursts into one reusable direct arena;
 * this compatibility implementation copies each frame once into the byte[] protocol core.
 */
public final class DpdkDevice implements PacketDevice {
    static { if (System.getProperty("os.name").toLowerCase().contains("linux")) System.loadLibrary("wirefin_dpdk"); }
    private final long handle; private final int batchSize, frameSize; private final ByteBuffer rxArena, txArena;
    private final int[] lengths; private final ArrayDeque<byte[]> pending = new ArrayDeque<>();
    private final MacAddress localMac, peerMac;
    private final AtomicBoolean closed = new AtomicBoolean();

    public DpdkDevice(Config config) throws IOException {
        if (!System.getProperty("os.name").toLowerCase().contains("linux")) throw new IOException("DPDK backend requires Linux");
        this.batchSize = config.batchSize; this.frameSize = config.frameSize; this.localMac = config.localMac; this.peerMac = config.peerMac;
        rxArena = ByteBuffer.allocateDirect(Math.multiplyExact(batchSize, frameSize));
        txArena = ByteBuffer.allocateDirect(Math.multiplyExact(batchSize, frameSize)); lengths = new int[batchSize];
        handle = nativeOpen(config.ealArguments, config.portId, config.rxQueue, config.txQueue,
                config.rxDescriptors, config.txDescriptors, config.mbufCount, frameSize);
        if (handle == 0) throw new IOException("DPDK EAL/port initialization failed");
    }

    @Override public byte[] read() throws IOException {
        while (pending.isEmpty()) { PacketBatch batch = new PacketBatch(batchSize); receive(batch); for (int i=0;i<batch.size();i++) pending.add(batch.get(i)); }
        return pending.removeFirst();
    }
    @Override public int receive(PacketBatch batch) throws IOException {
        batch.clear(); int count = nativeReceive(handle, rxArena, frameSize, lengths, Math.min(batch.capacity(), batchSize));
        if (count < 0) throw new IOException("DPDK RX burst failed: " + count);
        for (int i = 0; i < count; i++) {
            if (lengths[i] < 14 || lengths[i] > frameSize) continue;
            byte[] wire = new byte[lengths[i]]; rxArena.get(i * frameSize, wire);
            EthernetFrame frame;
            try { frame = EthernetCodec.parse(wire); } catch (IllegalArgumentException malformed) { continue; }
            if (frame.etherType() == EthernetFrame.IPV4 || frame.etherType() == EthernetFrame.IPV6) batch.add(frame.payload());
        }
        return batch.size();
    }
    @Override public void write(byte[] packet) throws IOException { PacketBatch batch = new PacketBatch(1); batch.add(packet); if (transmit(batch) != 1) throw new IOException("DPDK TX ring full"); }
    @Override public int transmit(PacketBatch batch) throws IOException {
        txArena.clear(); int prepared = Math.min(batch.size(), batchSize);
        for (int i = 0; i < prepared; i++) {
            byte[] packet = batch.get(i); int version = packet.length == 0 ? -1 : (packet[0] >>> 4) & 15;
            int type = version == 4 ? EthernetFrame.IPV4 : version == 6 ? EthernetFrame.IPV6 : -1;
            if (type < 0) throw new IOException("DPDK backend accepts only IPv4/IPv6 packets");
            byte[] frame = EthernetCodec.serialize(new EthernetFrame(peerMac, localMac, type, packet));
            if (frame.length > frameSize) throw new IOException("frame exceeds configured stride");
            txArena.put(i * frameSize, frame); lengths[i] = frame.length;
        }
        int sent = nativeTransmit(handle, txArena, frameSize, lengths, prepared);
        if (sent < 0) throw new IOException("DPDK TX burst failed: " + sent); return sent;
    }
    @Override public void close() { if (closed.compareAndSet(false, true)) nativeClose(handle); }

    public record Config(String[] ealArguments, int portId, int rxQueue, int txQueue, int rxDescriptors,
                         int txDescriptors, int mbufCount, int batchSize, int frameSize,
                         MacAddress localMac, MacAddress peerMac) {
        public Config {
            ealArguments = ealArguments.clone();
            if (portId < 0 || rxQueue != 0 || txQueue != 0 || rxDescriptors < 64 || txDescriptors < 64 ||
                    mbufCount < rxDescriptors + txDescriptors || batchSize < 1 || batchSize > 256 || frameSize < 64 ||
                    localMac == null || peerMac == null) throw new IllegalArgumentException("invalid DPDK configuration");
        }
        @Override public String[] ealArguments() { return ealArguments.clone(); }
    }
    private static native long nativeOpen(String[] ealArguments, int port, int rxQueue, int txQueue,
                                          int rxDescriptors, int txDescriptors, int mbufs, int frameSize);
    private static native int nativeReceive(long handle, ByteBuffer arena, int stride, int[] lengths, int maxPackets);
    private static native int nativeTransmit(long handle, ByteBuffer arena, int stride, int[] lengths, int packetCount);
    private static native void nativeClose(long handle);
}
