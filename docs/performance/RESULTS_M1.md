# Measured results: checksum and copy reduction

Same-host JMH comparison on the Apple M1 environment recorded in
`BASELINE_M1.md`. Each row uses identical workload code and JVM settings.

| Workload | baseline ops/s | final ops/s | change | baseline B/op | final B/op | allocation change |
|---|---:|---:|---:|---:|---:|---:|
| Small TCP packet | 3,553,669 | 5,186,047 | +45.9% | 1,320 | 1,008 | −23.6% |
| UDP packet | 7,372,077 | 8,900,759 | +20.7% | 792 | 648 | −18.2% |
| ICMP echo (confirming rerun) | 4,773,698 | 5,032,172 | +5.4% | 1,104 | 976 | −11.6% |
| Full-size TCP serialization | 459,110 | 706,360 | +53.9% | 5,984 | 1,496 | −75.0% |
| 64-flow passive-open batch | 318,549 | 521,397 | +63.7% | 68,307 | 67,907 | −0.6% |
| IPv6 serialization | 28,086,998 | 39,581,001 | +40.9% | 328 | 160 | −51.2% |

Full-size TCP median/p95/p99 changed from 1.458/2.500/16.033 µs to
1.290/1.332/1.416 µs. Small TCP changed from 0.250/0.584/1.584 µs to
0.167/0.250/0.375 µs. UDP p99 changed from 0.291 to 0.250 µs; its median
and p95 were unchanged at 0.125 and 0.167 µs.

The IPv4 serialization throughput run had extreme baseline iteration variance;
only its stable allocation change (152 to 80 B/op, −47.4%) is reported. JMH
deliberately saturates one benchmark thread and does not establish application
CPU utilization. Linux `/usr/bin/time -v` output is part of the end-to-end
harness for that measurement. No CPU reduction is claimed from these data.

## Direct-memory header views

These views validate headers and retain a read-only reference to existing memory;
they are not yet the default connection path.

| Header | materialized ops/s | view ops/s | change | materialized B/op | view B/op | allocation change |
|---|---:|---:|---:|---:|---:|---:|
| IPv4 | 33,083,525 | 40,177,823 | +21.4% | 232 | 104 | −55.2% |
| IPv6 | 38,745,852 | 95,929,773 | +147.6% | 312 | 104 | −66.7% |

The view result measures fixed-header validation only. Accessing materialized
addresses or application payloads can add work, so it is not presented as an
end-to-end packet-rate improvement.

## TUN versus DPDK

No TUN-versus-DPDK number was produced on this host: macOS has neither Linux TUN
nor a DPDK-bound NIC. Ordinary hosted CI runners also do not expose a bindable
NIC/hugepages. `benchmark-linux.sh` emits equivalent workload JSON, server
metrics, GC/JVM environment, and CPU data for both backends on suitable hardware.
Until that experiment is run, the only defensible comparison is architectural;
there is no DPDK throughput, latency, CPU, or scalability claim.
