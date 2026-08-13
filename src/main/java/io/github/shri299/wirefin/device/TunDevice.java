package io.github.shri299.wirefin.device;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Structure;
import com.sun.jna.ptr.IntByReference;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * Minimal Linux TUN wrapper. Linux creates/configures the interface outside this
 * process; this class only attaches to it and exchanges raw IPv4 packets.
 */
public final class TunDevice implements PacketDevice {
    private static final int O_RDWR = 2;
    private static final long TUNSETIFF = 0x400454caL;
    private static final short IFF_TUN = 0x0001;
    private static final short IFF_NO_PI = 0x1000;
    private static final int MTU = 65_535;
    private final LibC libc;
    private final int fd;
    private final String name;

    public TunDevice(String requestedName) throws IOException {
        if (!System.getProperty("os.name").toLowerCase().contains("linux")) {
            throw new IOException("TUN transport requires Linux");
        }
        libc = Native.load("c", LibC.class);
        fd = libc.open("/dev/net/tun", O_RDWR);
        if (fd < 0) throw error("open /dev/net/tun");
        IfReq request = new IfReq();
        byte[] encoded = requestedName.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(encoded, 0, request.name, 0, Math.min(encoded.length, request.name.length - 1));
        request.flags = (short) (IFF_TUN | IFF_NO_PI);
        request.write();
        if (libc.ioctl(fd, TUNSETIFF, request) < 0) {
            libc.close(fd);
            throw error("TUNSETIFF " + requestedName);
        }
        request.read();
        int nul = 0;
        while (nul < request.name.length && request.name[nul] != 0) nul++;
        name = new String(request.name, 0, nul, StandardCharsets.US_ASCII);
    }

    public String name() { return name; }

    @Override public byte[] read() throws IOException {
        byte[] buffer = new byte[MTU];
        int count = libc.read(fd, buffer, buffer.length);
        if (count < 0) throw error("read " + name);
        return Arrays.copyOf(buffer, count);
    }

    @Override public void write(byte[] packet) throws IOException {
        int count = libc.write(fd, packet, packet.length);
        if (count < 0) throw error("write " + name);
        if (count != packet.length) throw new IOException("short TUN packet write: " + count + "/" + packet.length);
    }

    @Override public void close() throws IOException {
        if (libc.close(fd) != 0) throw error("close " + name);
    }

    private static IOException error(String operation) {
        return new IOException(operation + " failed (errno=" + Native.getLastError() + ")");
    }

    public static final class IfReq extends Structure {
        public byte[] name = new byte[16];
        public short flags;
        public byte[] padding = new byte[22];
        @Override protected List<String> getFieldOrder() { return List.of("name", "flags", "padding"); }
    }

    interface LibC extends Library {
        int open(String path, int flags);
        int ioctl(int fd, long request, IfReq value);
        int read(int fd, byte[] buffer, int count);
        int write(int fd, byte[] buffer, int count);
        int close(int fd);
    }
}
