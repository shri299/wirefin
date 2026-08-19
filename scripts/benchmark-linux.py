#!/usr/bin/env python3
"""Small dependency-free load generator for the Linux end-to-end harness."""

import argparse
import json
import socket
import threading
import time


parser = argparse.ArgumentParser()
parser.add_argument("workload", choices=["small-tcp", "stream", "udp"])
parser.add_argument("host")
parser.add_argument("port", type=int)
parser.add_argument("--seconds", type=float, default=10)
parser.add_argument("--payload", type=int, default=1400)
parser.add_argument("--flows", type=int, default=1)
args = parser.parse_args()
if args.seconds <= 0 or args.payload < 0 or args.flows <= 0:
    parser.error("seconds and flows must be positive; payload cannot be negative")

deadline = time.monotonic() + args.seconds
lock = threading.Lock()
total_bytes = 0
total_operations = 0
latencies = []
failures = []


def record(size, latency_us=None):
    global total_bytes, total_operations
    with lock:
        total_bytes += size
        total_operations += 1
        if latency_us is not None:
            latencies.append(latency_us)


def tcp_stream():
    sock = socket.create_connection((args.host, args.port), timeout=5)
    data = bytes(args.payload)
    try:
        while time.monotonic() < deadline:
            sock.sendall(data)
            record(len(data))
    finally:
        sock.close()


def small_tcp():
    data = bytes(args.payload)
    while time.monotonic() < deadline:
        start = time.perf_counter_ns()
        sock = socket.create_connection((args.host, args.port), timeout=5)
        try:
            sock.sendall(data)
        finally:
            sock.close()
        record(len(data), (time.perf_counter_ns() - start) / 1000)


def udp():
    family = socket.AF_INET6 if ":" in args.host else socket.AF_INET
    sock = socket.socket(family, socket.SOCK_DGRAM)
    sock.settimeout(5)
    data = bytes(args.payload)
    try:
        while time.monotonic() < deadline:
            start = time.perf_counter_ns()
            sock.sendto(data, (args.host, args.port))
            reply, _ = sock.recvfrom(65535)
            if reply != data:
                raise RuntimeError("UDP echo mismatch")
            record(len(data), (time.perf_counter_ns() - start) / 1000)
    finally:
        sock.close()


def guarded(target):
    try:
        target()
    except Exception as failure:  # Propagate worker failures after joining all peers.
        with lock:
            failures.append(repr(failure))


target = {"small-tcp": small_tcp, "stream": tcp_stream, "udp": udp}[args.workload]
threads = [threading.Thread(target=guarded, args=(target,)) for _ in range(args.flows)]
started = time.monotonic()
for thread in threads:
    thread.start()
for thread in threads:
    thread.join()
elapsed = time.monotonic() - started
if failures:
    raise SystemExit("worker failure(s): " + "; ".join(failures))

result = {
    "workload": args.workload,
    "seconds": elapsed,
    "operations": total_operations,
    "packets_per_second": total_operations / elapsed,
    "bytes_per_second": total_bytes / elapsed,
    "bits_per_second": 8 * total_bytes / elapsed,
    "flows": args.flows,
    "payload_bytes": args.payload,
}
if latencies:
    latencies.sort()
    count = len(latencies)
    percentile = lambda value: latencies[min(count - 1, int((count - 1) * value))]
    result.update({
        "latency_median_us": percentile(0.50),
        "latency_p95_us": percentile(0.95),
        "latency_p99_us": percentile(0.99),
        "latency_samples": count,
    })
print(json.dumps(result, sort_keys=True))
