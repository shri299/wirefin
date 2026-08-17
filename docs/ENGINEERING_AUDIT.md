# Deep-engineering audit

Audit target: merged `main` at `a56e3dc`, Java 21. The untouched build completed
with 65 tests and no failures. This document distinguishes code that exists from
behavior that has been measured on suitable hardware.

## Executive findings

Wirefin has a coherent shared IPv4/IPv6 protocol core, a real Linux TUN
interoperability test, bounded application queues, RFC-6298-style retransmission
timing, NewReno-style recovery, JMH/JFR tooling, and an optional DPDK JNI shim.
The largest credibility gaps are not additional protocol features. They are
adversarial testing, operational diagnostics, explicit packet-memory ownership,
and real-hardware evidence.

The DPDK backend is currently a compatibility implementation: one RX/TX queue,
static neighbor mapping, an mbuf-to-direct-arena copy, and then Java
materialization. It has not been built or measured on a DPDK host. The current
`PacketBuffer` checks pool membership on recycle but exposes a mutable
`ByteBuffer` whose lifetime cannot be invalidated after return. `TcpStack` scans
all connections every 100 ms from a scheduler and shares state through
synchronized control blocks and a concurrent connection map. These choices are
reasonable for the measured scale; they are not a multi-core ownership model.

## Requested-area classification

| # | Area | Status | Evidence and decision |
|---:|---|---|---|
| 1 | Real DPDK environment | Partially implemented | Manual Ubuntu, hugepage, VFIO, binding, build, run, and restore commands exist. Safe discovery/validation/setup scripts and production-interface guards are missing. P0. |
| 2 | TUN vs DPDK benchmark | Partially implemented | Matched workload harness covers small TCP, stream, UDP, and 32 flows with `/usr/bin/time`; it lacks 1/10/100+ flow matrices, full metadata, GC/allocation collation, and real DPDK results. P0 tooling; measurements require hardware. |
| 3 | Storage-neutral packet memory | Missing | `byte[]`, direct pooled buffers, and DPDK arenas are separate APIs. Introduce explicit owned memory before further DPDK optimization. P1. |
| 4 | mbuf-backed views | Missing | Header views accept `ByteBuffer`, but JNI copies mbufs into a reusable arena and then `byte[]`. Requires native lifetime integration. P1 after ownership API. |
| 5 | Packet ownership | Partially implemented | `PacketBuffer` is bounded and idempotently recycled, but retained `ByteBuffer` references can outlive ownership and slices have no shared lifetime. P0/P1. |
| 6 | Multi-core processing | Missing | One Java packet loop, one timer thread, and one DPDK queue pair. Do not add workers before flow ownership and multi-core measurements. P2. |
| 7 | Flow affinity/RSS | Missing | Four-tuple keys exist, but no stable software hash, worker sharding, or RSS configuration. P2 with multi-queue DPDK. |
| 8 | NUMA operation | Missing | DPDK uses the current socket for one mempool; metadata and locality policy are absent. P2 and hardware-gated. |
| 9 | Congestion-control architecture | Partially implemented | A strategy interface and NewReno-style controller already exist. Selection/configuration and a second controller are missing. CUBIC is P1 experimentation and must not be called Linux-equivalent. |
| 10 | Netem impairment lab | Missing | Linux interoperability exists without loss/delay/jitter/duplication/reordering/bandwidth scenarios. P0. |
| 11 | Linux differential TCP tests | Partially implemented | Active Wirefin-to-Linux and passive Linux-to-Wirefin flows exist; packet/semantic comparison under impairment is missing. P0/P1. |
| 12 | Property-based tests | Missing | Focused example tests cover wraparound and codecs, but no generated sequence, reassembly, round-trip, or mutation properties. P0. |
| 13 | Parser fuzzing | Missing | Malformed examples exist; no seeded fuzz harness or retained corpus. P0. |
| 14 | Resource exhaustion | Partially implemented | Listener, UDP, send, fragment, and packet-pool bounds exist. The connection table and retransmission state lack global limits; flood/churn tests and rejection metrics are incomplete. P0. |
| 15 | Timer scalability | Partially implemented | RTO, persist, and TIME_WAIT semantics exist, but all connections are scanned every 100 ms. Benchmark 1k/10k/100k before considering a timing wheel. P1 evidence; wheel not worth implementing yet. |
| 16 | Structured packet tracing | Partially implemented | JUL `FINE` strings exist inside protocol code. There is no structured sink, stable connection ID, or truly disabled fast path. P0. |
| 17 | Connection introspection | Partially implemented | Individual getters expose much of the TCB, but there is no immutable snapshot/listing API and SRTT/RTTVAR/counters are absent. P0. |
| 18 | PCAP export | Missing | Linux tests use external `tcpdump`; the stack has no optional PCAP writer or direction metadata. P0. |
| 19 | Aggregate observability | Partially implemented | Packet/byte/drop/recovery/pool/batch gauges exist. Lifecycle, protocol, malformed/checksum, fragment, zero-window, rate, and precise queue metrics are missing. P0. |
| 20 | Scalability graphs/data | Partially implemented | A single many-flow workload exists; core/flow matrices and graph generation are missing. P0 tooling, hardware-gated results. |
| 21 | Performance regression detection | Missing | Baseline/final Markdown and JMH JSON exist, but no explicit baseline comparator. P0, initially advisory. |
| 22 | Architecture document | Missing | README contains useful diagrams but not the requested end-to-end architecture and ownership guide. P0. |
| 23 | Protocol support matrix | Partially implemented | README has IPv4/IPv6 and TCP matrices but lacks consistent test/DPDK evidence columns. P0. |
| 24 | Reproducible demo | Partially implemented | HTTP example and TUN script exist; combined live TCB/metrics plus netem recovery demonstration is missing. P1. |
| 25 | Scope discipline | Already implemented | The repository remains focused on userspace packet processing and transport protocols. Preserve this boundary. |
| 26 | Evidence rules | Partially implemented | Performance docs correctly qualify current measurements. Extend the same discipline to new DPDK, CUBIC, and scaling work. |
| 27 | Incremental work | Already implemented | History is composed of focused benchmark, performance, link, DPDK, test, and documentation commits. Continue this practice. |
| 28 | Audit before implementation | Implemented by this document | Revisit status after each PR-sized tranche. |

## Prioritized implementation plan

### P0 — correctness and evidence

1. Add safe DPDK host discovery/setup/validation/launch/restore scripts. Require an
   explicit PCI BDF and refuse an interface carrying the default route unless the
   operator supplies a separate force flag.
2. Add generated protocol properties, deterministic parser fuzzing, and bounded
   resource-exhaustion tests. Preserve failing seeds as regression inputs.
3. Add immutable TCB snapshots, an optional structured trace sink, and optional
   PCAP export whose disabled state adds only a null/constant branch.
4. Expand counters for connection lifecycle, protocol traffic, malformed/checksum
   rejection, fragments, zero windows, and resource rejection.
5. Add netem/differential/demo scripts, richer benchmark matrices and metadata,
   plus an advisory benchmark comparator.
6. Publish architecture and precise support/evidence matrices.

### P1 — architecture and measured optimization

1. Introduce storage-neutral packet memory with shared slice lifetime, checked
   release, heap/direct implementations, and ownership tests.
2. Adapt parsing views and the device boundary to owned memory. Materialize only
   where the connection/application contract still requires ownership.
3. Add an mbuf-backed implementation and batch release in JNI, then profile the
   Java/native boundary on real hardware before expanding it.
4. Add configurable NewReno-style/CUBIC selection. Treat Wirefin CUBIC as an
   educational implementation until differential and impairment results justify
   stronger wording.
5. Benchmark timer scans at 1k/10k/100k scheduled entries; implement a timing
   wheel only if scan cost is material.

### P2 — hardware-gated experimentation

1. Multi-queue, single-owner DPDK workers with stable five-tuple flow affinity.
2. NIC RSS configuration and verification against the software hash.
3. NUMA-local mempools, queue placement, CPU isolation, and cross-node diagnostics.
4. Measured 1/2/4/8-core TUN/DPDK scaling and congestion-control comparisons.

No P2 throughput or locality claim is valid until raw results, machine metadata,
and reproduction commands are committed from a suitable host.
