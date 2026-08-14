package io.github.shri299.wirefin.examples;

import io.github.shri299.wirefin.device.TunDevice;
import io.github.shri299.wirefin.ip.IpAddress;
import io.github.shri299.wirefin.ipv4.Ipv4Address;
import io.github.shri299.wirefin.ipv6.Ipv6Address;
import io.github.shri299.wirefin.runtime.TcpStack;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** Demonstrates Wirefin active-open TCP against a normal kernel TCP HTTP server. */
public final class HttpClient {
    private HttpClient() {}

    public static void main(String[] args) throws Exception {
        String deviceName = option(args, "--tun", "tun0");
        IpAddress localAddress = parseAddress(option(args, "--address", "10.0.0.2"));
        IpAddress remoteAddress = parseAddress(option(args, "--remote", "10.0.0.1"));
        int port = Integer.parseInt(option(args, "--port", "8080"));
        try (TcpStack stack = new TcpStack(new TunDevice(deviceName), java.util.List.of(localAddress))) {
            Thread packetLoop = Thread.ofVirtual().name("wirefin-client-packet-loop").start(() -> {
                try { stack.run(); }
                catch (Exception failure) { throw new RuntimeException(failure); }
            });
            try (var socket = stack.connect(remoteAddress, port, Duration.ofSeconds(5));
                 var response = new ByteArrayOutputStream()) {
                socket.write(("GET /wirefin.txt HTTP/1.1\r\nHost: " + remoteAddress +
                        "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                byte[] chunk = new byte[4096];
                int count;
                while ((count = socket.read(chunk)) >= 0) response.write(chunk, 0, count);
                System.out.print(response.toString(StandardCharsets.US_ASCII));
            } finally {
                stack.close();
                packetLoop.join(Duration.ofSeconds(2));
            }
        }
    }

    private static IpAddress parseAddress(String text) { return text.contains(":") ? Ipv6Address.parse(text) : Ipv4Address.parse(text); }

    private static String option(String[] args, String name, String fallback) {
        for (int i = 0; i + 1 < args.length; i++) if (args[i].equals(name)) return args[i + 1];
        return fallback;
    }
}
