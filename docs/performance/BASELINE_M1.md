# Baseline: Apple M1

Measured 2026-08-15 with OpenJDK 21.0.12 on an 8-core Apple M1 with 8 GiB
RAM and Darwin ARM64. These values describe isolated JVM protocol workloads,
not NIC or TUN throughput. Raw JSON is produced by `benchmark-jmh.sh`.

| Workload | Throughput (ops/s) | Allocation (B/op) | median (µs) | p95 (µs) | p99 (µs) |
|---|---:|---:|---:|---:|---:|
| IPv4 parse | 25,190,030 | 232 | 0.042 | 0.083 | 0.084 |
| IPv6 parse | 37,238,592 | 312 | 0.042 | 0.083 | 0.208 |
| IPv4 serialize | 8,994,474 | 152 | 0.042 | 0.042 | 0.083 |
| IPv6 serialize | 28,086,998 | 328 | 0.042 | 0.167 | 1.124 |
| UDP packet processing | 7,372,077 | 792 | 0.125 | 0.167 | 0.291 |
| ICMP echo processing | 4,773,698 | 1,104 | 0.208 | 0.333 | 1.040 |
| Small TCP packet | 3,553,669 | 1,320 | 0.250 | 0.584 | 1.584 |
| Full-size TCP serialize | 459,110 | 5,984 | 1.458 | 2.500 | 16.033 |
| 64-flow passive-open batch | 318,549 | 68,307 | 1.766 | 2.540 | 11.568 |

The full-size TCP row uses a corrected benchmark that constructs the immutable
segment during setup rather than charging object construction to serialization.
The IPv4 serialization run showed large iteration variance and should not be
used for a narrow percentage claim without rerunning on a controlled host. The
allocation result is stable. CPU utilization and TUN throughput require the
Linux end-to-end harness; they are intentionally not inferred from JMH.
