# Performance methodology

Performance changes are accepted only after the unchanged and modified revisions
are measured with identical commands on the same host. JMH isolates codec and
packet-processor costs; the Linux TUN harness measures system behavior. JMH runs
three one-second warmups, five one-second measurements, and a fresh fork.

```bash
JAVA_HOME=/path/to/jdk21 bash scripts/benchmark-jmh.sh target/benchmarks
JAVA_HOME=/path/to/jdk21 bash scripts/profile-jfr.sh target/benchmarks/jfr
```

Run throughput mode with the GC profiler for operations/second and allocated
bytes/operation. Sample-time mode records median, p95 and p99 latency. The result
directory includes exact OS, CPU, memory, architecture, timestamp, and JVM data.
Use JFR/JDK Mission Control to inspect allocation stacks, locks, scheduling,
checksums and codec hot methods before changing code.

For CPU utilization and kernel effects on Linux, use the end-to-end harness under
`/usr/bin/time -v`, capture `perf stat`, and retain both command output and packet
capture. Run with a fixed performance governor and record kernel, NIC, queue,
MTU, IRQ affinity, JVM flags, flow count, payload size, duration, and batch size.
Report TUN and DPDK separately. A DPDK result is valid only on a host with a bound
NIC, configured hugepages, and the same traffic generator/link conditions.

Never compare results from different hosts as a before/after percentage. Never
describe a path as zero-copy unless buffer ownership and every remaining copy are
identified.
