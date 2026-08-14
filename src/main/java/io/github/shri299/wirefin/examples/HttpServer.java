package io.github.shri299.wirefin.examples;

import io.github.shri299.wirefin.device.TunDevice;
import io.github.shri299.wirefin.ipv4.Ipv4Address;
import io.github.shri299.wirefin.ipv6.Ipv6Address;
import io.github.shri299.wirefin.runtime.TcpStack;
import io.github.shri299.wirefin.socket.TcpListener;
import io.github.shri299.wirefin.socket.TcpSocket;
import io.github.shri299.wirefin.socket.UdpSocket;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class HttpServer {
    private static final byte[] HEADER_END = "\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
    private HttpServer() {}

    public static void main(String[] args) throws Exception {
        String deviceName = option(args, "--tun", "tun0");
        Ipv4Address address = Ipv4Address.parse(option(args, "--address", "10.0.0.2"));
        String address6Text = option(args, "--address6", null);
        int port = Integer.parseInt(option(args, "--port", "8080"));
        int udpPort = Integer.parseInt(option(args, "--udp-port", Integer.toString(port)));
        if (has(args, "--debug")) enableDebugLogging();

        TunDevice device = new TunDevice(deviceName);
        var addresses = new java.util.ArrayList<io.github.shri299.wirefin.ip.IpAddress>(); addresses.add(address);
        if (address6Text != null) addresses.add(Ipv6Address.parse(address6Text));
        try (TcpStack stack = new TcpStack(device, addresses); UdpSocket udp = stack.bindUdp(udpPort)) {
            TcpListener listener = stack.listen(port);
            Thread.ofVirtual().name("wirefin-packet-loop").start(() -> {
                try { stack.run(); }
                catch (Exception e) { throw new RuntimeException(e); }
            });
            Thread.ofVirtual().name("wirefin-udp-echo").start(() -> {
                while (true) try {
                    var datagram = udp.receive(java.time.Duration.ofDays(1));
                    udp.sendTo(datagram.sourceAddress(), datagram.sourcePort(), datagram.payload());
                } catch (Exception stopped) { return; }
            });
            System.out.printf("Wirefin listening on TCP %s:%d and UDP echo port %d%s via %s%n", address, port,
                    udpPort, address6Text == null ? "" : " (IPv6 " + address6Text + ")", device.name());
            while (true) {
                TcpSocket socket = listener.accept();
                Thread.ofVirtual().name("wirefin-http").start(() -> serve(socket));
            }
        }
    }

    private static void serve(TcpSocket socket) {
        try (socket; var request = new ByteArrayOutputStream()) {
            byte[] chunk;
            while ((chunk = socket.read()) != null) {
                request.writeBytes(chunk);
                if (contains(request.toByteArray(), HEADER_END)) break;
            }
            byte[] body = "Hello from userspace TCP".getBytes(StandardCharsets.US_ASCII);
            byte[] response = ("HTTP/1.1 200 OK\r\nContent-Length: " + body.length +
                    "\r\nConnection: close\r\nContent-Type: text/plain\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
            var full = new ByteArrayOutputStream();
            full.writeBytes(response);
            full.writeBytes(body);
            socket.write(full.toByteArray());
        } catch (Exception e) {
            Logger.getLogger(HttpServer.class.getName()).log(Level.WARNING, "HTTP connection failed", e);
        }
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        outer: for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) if (haystack[i + j] != needle[j]) continue outer;
            return true;
        }
        return false;
    }

    private static String option(String[] args, String name, String fallback) {
        for (int i = 0; i + 1 < args.length; i++) if (args[i].equals(name)) return args[i + 1];
        return fallback;
    }
    private static boolean has(String[] args, String name) {
        for (String arg : args) if (arg.equals(name)) return true;
        return false;
    }
    private static void enableDebugLogging() {
        Logger root = Logger.getLogger("");
        root.setLevel(Level.FINE);
        for (var handler : root.getHandlers()) handler.setLevel(Level.FINE);
        if (root.getHandlers().length == 0) { var handler = new ConsoleHandler(); handler.setLevel(Level.FINE); root.addHandler(handler); }
    }
}
