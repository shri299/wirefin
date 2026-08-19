# Protocol support and evidence matrix

Legend: **implemented** is supported by the current runtime; **partial** is a
deliberate subset; **unsupported** is rejected or absent. “Linux” means exercised
by `scripts/integration-test-linux.sh` in the privileged Linux job. “Unit” includes
deterministic integration/property/fuzz tests. DPDK cells remain unverified until
real-host artifacts are committed.

| Area | Status | Unit evidence | Linux evidence | DPDK evidence | Important boundary |
|---|---|---:|---:|---:|---|
| IPv4 fixed header | implemented | yes | yes | no | Options retained; not a forwarding implementation |
| IPv4 fragmentation receive | partial | yes | no | no | Bounded reassembly; overlap drops assembly; no transmit fragmentation |
| IPv6 fixed header | implemented | yes | yes | no | Extension headers and fragmentation unsupported |
| IPv4/IPv6 routing table | implemented | yes | direct route | no | Runtime has one output device |
| ICMPv4 echo | implemented | yes | yes | no | Narrow diagnostic subset |
| ICMPv6 echo | implemented | yes | yes | no | No router advertisements |
| ICMP destination unreachable | implemented | yes | UDP path | no | Required port-unreachable forms only |
| UDP IPv4/IPv6 | implemented | yes | yes | no | Bounded socket queues; IPv6 checksum mandatory |
| TCP passive open | implemented | yes | yes, v4/v6 | no | Educational state machine, not full RFC coverage |
| TCP active open | implemented | yes | yes, v4 | no | IPv6 generic path unit-tested only |
| TCP ordered delivery/overlap | implemented | yes + generated properties | indirect | no | Fixed receive capacity |
| TCP retransmission/RTO | implemented | yes | base interop; netem lab available | no | RFC-6298-style estimator, not conformance-certified |
| NewReno-style recovery | partial | yes | netem lab available | no | RFC-5681/6582-inspired subset |
| SACK | partial | yes | netem lab available | no | Receiver blocks + sender scoreboard; not RFC 6675 recovery |
| Window scaling | implemented | yes | indirect | no | SYN negotiation only, as required |
| TCP timestamps | partial | yes | indirect | no | Negotiation/echo; no PAWS claim |
| Zero-window persist | implemented | yes | no | no | Deterministic tests; bounded exponential backoff |
| SYN backlog/cookies | partial | yes | no | no | Educational cookies omit advanced option state |
| TCP teardown/TIME_WAIT/RST | implemented | yes | yes | no | Challenge-ACK subset; configurable TIME_WAIT |
| TCP CUBIC | partial | yes | netem lab available | no | Educational cubic growth/decrease; not Linux-equivalent RFC 9438 |
| Ethernet codec | implemented | yes + fuzz/property | n/a for TUN | no | Used by DPDK compatibility boundary |
| ARP / IPv6 neighbor messages | partial | yes | no | no | Codecs/cache exist; DPDK TX still requires static peer MAC |
| DPDK one-queue backend | partial | compile-only where available | n/a | no real-host run committed | Copies through arena and `byte[]` core |
| Multi-queue/RSS/NUMA | unsupported | no | no | no | Architecture target only; hardware-gated |
| PCAPNG / structured trace | implemented | yes | lab scripts available | no | Optional synchronous diagnostic sinks |

Passing unit tests establishes invariants in modeled inputs, not interoperability.
The Linux job establishes only the exact direct-route scenarios it runs. A row
must not be marked DPDK-tested until it has machine metadata, setup commands, raw
results, and a trace from a suitable host.
