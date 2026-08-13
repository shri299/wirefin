package io.github.shri299.wirefin.runtime;

import io.github.shri299.wirefin.ipv4.Ipv4Address;
import io.github.shri299.wirefin.ipv4.Ipv4Codec;
import io.github.shri299.wirefin.ipv4.Ipv4Packet;
import io.github.shri299.wirefin.socket.TcpListener;
import io.github.shri299.wirefin.socket.TcpSocket;
import io.github.shri299.wirefin.tcp.TcpCodec;
import io.github.shri299.wirefin.tcp.TcpFlags;
import io.github.shri299.wirefin.tcp.TcpSegment;
import io.github.shri299.wirefin.tcp.state.TcpState;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.junit.jupiter.api.Assertions.*;

/** Simulates the complete curl-side packet flow without using a kernel TCP socket. */
class HttpFlowIntegrationTest {
    private static final Ipv4Address CLIENT = Ipv4Address.parse("10.0.0.1");
    private static final Ipv4Address SERVER = Ipv4Address.parse("10.0.0.2");

    @Test void handshakeRequestResponseAndFourWayClose() throws Exception {
        List<byte[]> asynchronous = new CopyOnWriteArrayList<>();
        PacketProcessor processor = new PacketProcessor(SERVER, () -> 1000, asynchronous::add);
        TcpListener listener = new TcpListener(processor, 8080);

        TcpSegment synAck = response(processor.process(packet(segment(2000, 0, TcpFlags.SYN, new byte[0]))).getFirst());
        assertEquals(TcpFlags.SYN | TcpFlags.ACK, synAck.flags());
        processor.process(packet(segment(2001, 1001, TcpFlags.ACK, new byte[0])));
        TcpSocket socket = listener.accept();

        byte[] request = "GET / HTTP/1.1\r\nHost: 10.0.0.2\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
        TcpSegment requestAck = response(processor.process(packet(segment(2001, 1001, TcpFlags.ACK | TcpFlags.PSH, request))).getFirst());
        assertEquals(2001 + request.length, requestAck.acknowledgementNumber());
        assertArrayEquals(request, socket.read());

        byte[] http = "HTTP/1.1 200 OK\r\nContent-Length: 24\r\n\r\nHello from userspace TCP".getBytes(StandardCharsets.US_ASCII);
        socket.write(http);
        socket.close();
        assertEquals(2, asynchronous.size());
        TcpSegment response = response(asynchronous.get(0));
        TcpSegment fin = response(asynchronous.get(1));
        assertArrayEquals(http, response.payload());
        assertTrue(fin.has(TcpFlags.FIN));

        long clientNext = 2001L + request.length;
        processor.process(packet(segment(clientNext, fin.sequenceNumber() + 1, TcpFlags.ACK, new byte[0])));
        List<byte[]> finalAck = processor.process(packet(segment(clientNext, fin.sequenceNumber() + 1,
                TcpFlags.FIN | TcpFlags.ACK, new byte[0])));
        assertEquals(1, finalAck.size());
        assertEquals(clientNext + 1, response(finalAck.getFirst()).acknowledgementNumber());
        assertEquals(TcpState.TIME_WAIT, socket.controlBlock().state());
    }

    private static TcpSegment segment(long sequence, long ack, int flags, byte[] payload) {
        return new TcpSegment(50000, 8080, sequence, ack, flags, 65535, 0, new byte[0], payload);
    }
    private static byte[] packet(TcpSegment segment) {
        byte[] tcp = TcpCodec.serialize(segment, CLIENT, SERVER);
        return Ipv4Codec.serialize(new Ipv4Packet(0, 1, 2, 0, 64, 6, CLIENT, SERVER, new byte[0], tcp));
    }
    private static TcpSegment response(byte[] packet) {
        Ipv4Packet ip = Ipv4Codec.parse(packet);
        return TcpCodec.parse(ip.payload(), SERVER, CLIENT);
    }
}
