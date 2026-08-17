package io.github.shri299.wirefin.bench;

import io.github.shri299.wirefin.icmp.*;
import io.github.shri299.wirefin.ipv4.*;
import io.github.shri299.wirefin.ipv6.*;
import io.github.shri299.wirefin.runtime.PacketProcessor;
import io.github.shri299.wirefin.tcp.*;
import io.github.shri299.wirefin.tcp.connection.TcpConnection;
import io.github.shri299.wirefin.udp.*;
import io.github.shri299.wirefin.view.*;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Isolated protocol costs. Run both throughput and sample-time modes via scripts/benchmark-jmh.sh. */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class ProtocolBenchmark {
    @State(Scope.Thread)
    public static class Packets {
        final Ipv4Address remote4 = Ipv4Address.parse("192.0.2.1"), local4 = Ipv4Address.parse("192.0.2.2");
        final Ipv6Address remote6 = Ipv6Address.parse("2001:db8::1"), local6 = Ipv6Address.parse("2001:db8::2");
        final byte[] payload = new byte[1_460];
        Ipv4Packet ipv4;
        Ipv6Packet ipv6;
        TcpSegment fullTcp;
        byte[] ipv4Wire, ipv6Wire, tcpSynWire, udpWire, icmpWire;
        java.nio.ByteBuffer ipv4Memory, ipv6Memory;
        PacketProcessor udpProcessor, tcpProcessor;

        @Setup(Level.Trial) public void setup() {
            byte[] udp = UdpCodec.serialize(new UdpDatagram(40_000, 9_000, new byte[32]), remote4, local4);
            fullTcp = new TcpSegment(1, 2, 3, 4, TcpFlags.ACK, 65_535, 0, new byte[0], payload);
            ipv4 = new Ipv4Packet(0, 7, 2, 0, 64, 17, remote4, local4, new byte[0], udp);
            ipv4Wire = Ipv4Codec.serialize(ipv4);
            ipv4Memory = java.nio.ByteBuffer.wrap(ipv4Wire);
            ipv6 = new Ipv6Packet(0, 0, 17, 64, remote6, local6,
                    UdpCodec.serialize(new UdpDatagram(40_000, 9_000, new byte[32]), remote6, local6));
            ipv6Wire = Ipv6Codec.serialize(ipv6);
            ipv6Memory = java.nio.ByteBuffer.wrap(ipv6Wire);
            tcpSynWire = Ipv4Codec.serialize(new Ipv4Packet(0, 8, 2, 0, 64, 6, remote4, local4, new byte[0],
                    TcpCodec.serialize(new TcpSegment(40_001, 9_001, 1, 0, TcpFlags.SYN, 65_535, 0,
                            new byte[0], new byte[0]), remote4, local4)));
            udpWire = ipv4Wire;
            icmpWire = Ipv4Codec.serialize(new Ipv4Packet(0, 9, 2, 0, 64, 1, remote4, local4, new byte[0],
                    IcmpCodec.serializeV4(new IcmpMessage(8, 0, 1, new byte[32]))));
            udpProcessor = processor(); udpProcessor.bindUdp(9_000, ignored -> {});
            tcpProcessor = processor();
        }
        PacketProcessor processor() {
            return new PacketProcessor(List.of(local4, local6), () -> 100, ignored -> {}, System::nanoTime,
                    TcpConnection.Config.defaults());
        }
    }

    @Benchmark public Ipv4Packet parseIpv4(Packets state) { return Ipv4Codec.parse(state.ipv4Wire); }
    @Benchmark public byte[] serializeIpv4(Packets state) { return Ipv4Codec.serialize(state.ipv4); }
    @Benchmark public Ipv6Packet parseIpv6(Packets state) { return Ipv6Codec.parse(state.ipv6Wire); }
    @Benchmark public Ipv4PacketView viewIpv4(Packets state) { return Ipv4PacketView.parse(state.ipv4Memory, 0, state.ipv4Wire.length); }
    @Benchmark public Ipv6PacketView viewIpv6(Packets state) { return Ipv6PacketView.parse(state.ipv6Memory, 0, state.ipv6Wire.length); }
    @Benchmark public byte[] serializeIpv6(Packets state) { return Ipv6Codec.serialize(state.ipv6); }
    @Benchmark public byte[] serializeFullSizeTcp(Packets state) {
        return TcpCodec.serialize(state.fullTcp, state.local4, state.remote4);
    }
    @Benchmark public void processUdpDatagram(Packets state, Blackhole blackhole) {
        blackhole.consume(state.udpProcessor.process(state.udpWire));
    }
    @Benchmark public void processIcmpEcho(Packets state, Blackhole blackhole) {
        blackhole.consume(state.udpProcessor.process(state.icmpWire));
    }
    @Benchmark public void processSmallTcpPacket(Packets state, Blackhole blackhole) {
        blackhole.consume(state.tcpProcessor.process(state.tcpSynWire));
    }

    @State(Scope.Thread)
    public static class Flows {
        final Packets packets = new Packets();
        PacketProcessor processor;
        byte[][] syns;
        @Setup(Level.Trial) public void setupPackets() {
            packets.setup(); syns = new byte[64][];
            for (int i = 0; i < syns.length; i++) {
                var tcp = new TcpSegment(20_000 + i, 8_080, i + 1, 0, TcpFlags.SYN, 65_535, 0, new byte[0], new byte[0]);
                syns[i] = Ipv4Codec.serialize(new Ipv4Packet(0, i, 2, 0, 64, 6, packets.remote4, packets.local4,
                        new byte[0], TcpCodec.serialize(tcp, packets.remote4, packets.local4)));
            }
        }
        @Setup(Level.Invocation) public void resetProcessor() { processor = packets.processor(); processor.listen(8_080); }
    }
    @Benchmark @OperationsPerInvocation(64)
    public void establishManyFlows(Flows state, Blackhole blackhole) {
        for (byte[] syn : state.syns) blackhole.consume(state.processor.process(syn));
    }
}
