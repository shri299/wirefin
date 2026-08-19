# Linux impairment and differential TCP lab

`scripts/netem-lab.sh` runs the same HTTP completion check against Wirefin and a
Linux TCP reference under a named `tc netem` scenario. It preserves curl timing,
Wirefin's structured TCP trace, and a directly emitted PCAPNG capture.

The impairment is intentionally one-way: client-to-server egress. For Wirefin,
the qdisc is attached to the host side of its dedicated TUN interface. For the
Linux reference, it is attached to the client veth in an isolated pair of network
namespaces. Thus the direction is comparable, although scheduling and paths are
not identical. The 10/50/100 ms names approximate RTT by applying half that delay
in one direction; they are not measurements of observed RTT.

## Run

On a disposable Linux development host with Java 21, Maven, `iproute2`, curl,
and sudo access:

```bash
./scripts/netem-lab.sh all target/netem-lab
./scripts/netem-lab.sh loss-1 target/netem-loss-1
```

The script refuses to reuse an existing TUN name and removes only the TUN,
qdisc, veth pair, and namespaces it created. Set `WIREFIN_REFERENCE=false` to
skip the kernel comparison. Supported scenarios are clean, 0.1/1/5 percent
loss, 10/50/100 ms nominal RTT, jitter, duplication, reordering, and a 1 Mbit/s
rate limit.

Each Wirefin directory contains:

- `curl.json` and `body.txt`: application completion and timing evidence;
- `trace.jsonl`: per-segment TCB state including SEQ/ACK, cwnd, RTO, and the
  cumulative retransmission counter;
- `trace-summary.json`: semantic summary produced by
  `scripts/summarize-trace.py`;
- `session.pcapng`: nanosecond raw-IP RX/TX capture suitable for Wireshark;
- `server.log`: stack diagnostics.

The Linux reference intentionally checks semantic equivalence—establishment,
correct response bytes, and clean completion. Wirefin's structured trace adds
ACK progression and recovery evidence. Packet sequences, cwnd values, timers,
and completion latency are not expected to match Linux exactly.

## Evidence boundaries

This lab validates a short HTTP flow. A random loss scenario may complete without
losing a TCP segment; inspect `maximum_observed_retransmissions` before claiming
recovery occurred. A deterministic packet-drop proxy is needed for a guaranteed
fast-retransmit demonstration. Zero-window behavior remains covered by
deterministic protocol tests rather than this HTTP lab. The scripts have been
syntax-checked on non-Linux development machines, but successful results must be
recorded on Linux before claiming a scenario passed.
