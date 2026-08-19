package io.github.shri299.wirefin.examples;

import io.github.shri299.wirefin.device.*;
import io.github.shri299.wirefin.ip.IpAddress;
import io.github.shri299.wirefin.ipv4.Ipv4Address;
import io.github.shri299.wirefin.ipv6.Ipv6Address;
import io.github.shri299.wirefin.link.MacAddress;
import io.github.shri299.wirefin.runtime.TcpStack;
import io.github.shri299.wirefin.tcp.congestion.CongestionControlAlgorithm;
import io.github.shri299.wirefin.tcp.connection.TcpConnection;
import io.github.shri299.wirefin.trace.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.LongAdder;

/** Bounded-duration TCP drain and UDP echo target for external Linux load generators. */
public final class PerformanceServer {
    private PerformanceServer() {}
    public static void main(String[] args) throws Exception {
        String backend=option(args,"--backend","tun"), address4=option(args,"--address","10.77.0.2"), address6=option(args,"--address6","fd00:77::2");
        int tcpPort=Integer.parseInt(option(args,"--tcp-port","19080")),udpPort=Integer.parseInt(option(args,"--udp-port","19081"));
        int seconds=Integer.parseInt(option(args,"--duration","30")),batch=Integer.parseInt(option(args,"--batch","32"));
        PacketDevice device;
        if(backend.equals("tun")) device=new TunDevice(option(args,"--tun","wf-perf0"));
        else if(backend.equals("dpdk")) device=new DpdkDevice(new DpdkDevice.Config(option(args,"--eal-args","wirefin -l 1-2").split("\\s+"),
                Integer.parseInt(option(args,"--port-id","0")),0,0,1024,1024,8191,batch,2048,
                MacAddress.parse(required(args,"--local-mac")),MacAddress.parse(required(args,"--peer-mac"))));
        else throw new IllegalArgumentException("backend must be tun or dpdk");
        List<IpAddress> addresses=List.of(Ipv4Address.parse(address4),Ipv6Address.parse(address6)); LongAdder appBytes=new LongAdder();
        String pcap=option(args,"--pcap",null),trace=option(args,"--trace",null);
        PacketCapture capture=pcap==null?PacketCapture.disabled():new PcapNgWriter(java.nio.file.Path.of(pcap));
        ProtocolTracer tracer=trace==null?ProtocolTracer.disabled():new JsonLineProtocolTracer(java.nio.file.Path.of(trace));
        boolean detailedMetrics=Boolean.parseBoolean(option(args,"--detailed-metrics","true"));
        var congestion=CongestionControlAlgorithm.parse(option(args,"--congestion-control","reno"));
        var connectionConfig=TcpConnection.Config.defaults().withCongestionControl(congestion);
        TcpStack stack=new TcpStack(device,addresses,batch,capture,tracer,detailedMetrics,connectionConfig); var listener=stack.listen(tcpPort,4096,true); var udp=stack.bindUdp(udpPort);
        Thread loop=Thread.ofPlatform().name("wirefin-event-loop").start(()->{try{stack.run();}catch(Exception failure){String message=failure.getMessage();if(message==null||!message.contains("closed"))failure.printStackTrace();}});
        Thread.ofVirtual().start(()->{while(true)try{var socket=listener.accept();Thread.ofVirtual().start(()->{try(socket){byte[] block=new byte[64*1024];int count;while((count=socket.read(block))>=0)appBytes.add(count);}catch(Exception ignored){}});}catch(Exception stopped){return;}});
        Thread.ofVirtual().start(()->{while(true)try{var datagram=udp.receive(Duration.ofSeconds(seconds+5L));appBytes.add(datagram.payload().length);udp.sendTo(datagram.sourceAddress(),datagram.sourcePort(),datagram.payload());}catch(Exception stopped){return;}});
        System.out.printf("Wirefin performance server ready backend=%s tcp=%d udp=%d batch=%d congestion=%s%n",backend,tcpPort,udpPort,batch,congestion.name().toLowerCase());
        Thread.sleep(Duration.ofSeconds(seconds)); var snapshot=stack.metrics(); stack.close(); loop.join(Duration.ofSeconds(2));
        System.out.printf(Locale.ROOT,"{\"backend\":\"%s\",\"duration_seconds\":%d,\"application_bytes\":%d,\"rx_packets\":%d,\"tx_packets\":%d,\"rx_bytes\":%d,\"tx_bytes\":%d,\"drops\":%d,\"retransmissions\":%d,\"fast_retransmits\":%d,\"rto_events\":%d,\"connections_opened\":%d,\"connections_closed\":%d,\"malformed_packets\":%d,\"checksum_failures\":%d,\"resource_rejections\":%d,\"average_batch_size\":%.3f,\"packets_per_second\":%.3f,\"gigabits_per_second\":%.6f}%n",
                backend,seconds,appBytes.sum(),snapshot.rxPackets(),snapshot.txPackets(),snapshot.rxBytes(),snapshot.txBytes(),snapshot.drops(),snapshot.retransmissions(),snapshot.fastRetransmits(),snapshot.rtoEvents(),snapshot.connectionsOpened(),snapshot.connectionsClosed(),snapshot.malformedPackets(),snapshot.checksumFailures(),snapshot.resourceRejections(),snapshot.averageBatchSize(),snapshot.packetsPerSecond(),snapshot.gigabitsPerSecond());
    }
    private static String option(String[] args,String name,String fallback){for(int i=0;i+1<args.length;i++)if(args[i].equals(name))return args[i+1];return fallback;}
    private static String required(String[] args,String name){String value=option(args,name,null);if(value==null)throw new IllegalArgumentException("missing "+name);return value;}
}
