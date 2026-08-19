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

The end-to-end matrix is generated with:

```bash
WIREFIN_BACKEND=tun WIREFIN_DURATION=30 \
  WIREFIN_FLOWS="1 10 100" ./scripts/benchmark-linux.sh target/bench-tun

WIREFIN_BACKEND=dpdk DPDK_PORT_ID=0 \
  DPDK_LOCAL_MAC=02:00:00:00:00:02 DPDK_PEER_MAC=02:00:00:00:00:01 \
  DPDK_EAL_ARGS="wirefin -l 2-3 --socket-mem 1024" \
  WIREFIN_GENERATOR_SSH=loadgen WIREFIN_GENERATOR_DIR=/opt/wirefin \
  ./scripts/benchmark-linux.sh target/bench-dpdk
```

For every configured flow count, the harness runs connection-per-operation TCP
with a 64-byte body, sustained TCP at 64 and 1460 bytes, and UDP echo at 64 and
1400 bytes. `benchmark.json` includes exact results, configuration, commit,
kernel/JDK, CPU, memory, NIC, NUMA, DPDK, GC-log, JFR, and whole-process GNU time
evidence where those tools exist. `scalability.csv` is graph-ready. Missing host
tools produce `null` metadata rather than invented values. CPU, allocation, and
GC evidence currently covers the complete server process, so it must not be
attributed to an individual workload row.

DPDK runs require a second host (or otherwise independent traffic-generator
endpoint) configured to reach the DPDK port. The harness refuses local DPDK load
generation because kernel sockets on the DUT cannot exercise a NIC bound to
VFIO. `WIREFIN_GENERATOR_SSH` names that peer and `WIREFIN_GENERATOR_DIR` points
to an equivalent checkout there; its basic CPU/link metadata is retained too.

Compare an explicit baseline and candidate in advisory mode:

```bash
./scripts/compare-benchmarks.py baseline/benchmark.json candidate/benchmark.json
```

The defaults flag throughput losses over 5%, allocation increases over 10%, and
p99 increases over 10%. The command reports but exits successfully unless
`--fail` is deliberately selected. End-to-end variance must be characterized on
the benchmark host before using that option in CI.

Timer scalability is measured separately at 1k, 10k, and 100k synthetic active
connections:

```bash
./scripts/benchmark-timers.sh target/timer-benchmark
```

This measures the current linear not-due timer poll, including each control
block's synchronized timer check. It does not simulate packet work or claim a
production flow capacity. A hashed timing wheel remains intentionally
unimplemented until same-host results show that this scan is material to the
target workload.

Never compare results from different hosts as a before/after percentage. Never
describe a path as zero-copy unless buffer ownership and every remaining copy are
identified.
