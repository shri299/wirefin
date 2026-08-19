package io.github.shri299.wirefin.bench;

import io.github.shri299.wirefin.ipv4.Ipv4Address;
import io.github.shri299.wirefin.tcp.connection.TcpConnection;
import io.github.shri299.wirefin.tcp.connection.TcpConnectionKey;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/** Measures the existing linear timer poll before any timing-wheel decision. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 4, time = 1)
@Fork(value = 1, jvmArgsAppend = {"-Xms1g", "-Xmx1g"})
public class TimerScanBenchmark {
    @State(Scope.Thread)
    public static class Timers {
        @Param({"1000", "10000", "100000"}) public int connectionCount;
        TcpConnection[] connections;

        @Setup(Level.Trial) public void setup() {
            connections = new TcpConnection[connectionCount];
            Ipv4Address local = Ipv4Address.parse("192.0.2.2");
            TcpConnection.Config defaults = TcpConnection.Config.defaults();
            TcpConnection.Config synthetic = new TcpConnection.Config(1, defaults.localMss(), defaults.defaultPeerMss(),
                    defaults.initialRto(), defaults.minimumRto(), defaults.maximumRto(), defaults.timeWaitDuration(),
                    defaults.persistInitial(), defaults.persistMaximum(), defaults.windowScalingEnabled(),
                    defaults.localWindowScale(), defaults.timestampsEnabled(), defaults.sackEnabled(),
                    defaults.maximumSynTransmissions(), 1);
            for (int i = 0; i < connectionCount; i++) {
                Ipv4Address remote = new Ipv4Address(0xc6120000 | i); // 198.18.0.0/15 benchmark space.
                var key = new TcpConnectionKey(local, 40_000, remote, 1 + i % 65_534);
                connections[i] = TcpConnection.activeOpen(key, i, 0, synthetic);
            }
        }
    }

    @Benchmark public void scanNotDueTimers(Timers state, Blackhole blackhole) {
        for (TcpConnection connection : state.connections)
            blackhole.consume(connection.retransmissionsDue(1));
    }
}
