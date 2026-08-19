#!/usr/bin/env python3
"""Collate benchmark cases with environment metadata and graph-ready CSV."""

import argparse
import csv
import json
import os
import pathlib
import platform
import shutil
import subprocess


def command(*args):
    try:
        return subprocess.run(args, check=False, text=True, stdout=subprocess.PIPE,
                              stderr=subprocess.STDOUT).stdout.strip() or None
    except OSError:
        return None


parser = argparse.ArgumentParser()
parser.add_argument("result_dir", type=pathlib.Path)
parser.add_argument("--backend", required=True)
parser.add_argument("--batch", type=int, required=True)
parser.add_argument("--jvm-flags", default="")
parser.add_argument("--output-json", type=pathlib.Path, required=True)
parser.add_argument("--output-csv", type=pathlib.Path, required=True)
args = parser.parse_args()

results = [json.loads(path.read_text()) for path in sorted((args.result_dir / "results").glob("*.json"))]
for result in results:
    result["backend"] = args.backend
    result["batch_size_configured"] = args.batch

metadata = {
    "wirefin_commit": command("git", "rev-parse", "HEAD"),
    "wirefin_tree_dirty": bool(command("git", "status", "--porcelain")),
    "kernel": platform.platform(),
    "jdk": command("java", "-version"),
    "jvm_flags": args.jvm_flags,
    "cpu": command("lscpu"),
    "memory": command("free", "-h"),
    "numa": command("numactl", "--hardware") if shutil.which("numactl") else None,
    "nics": command("lspci", "-nnk") if shutil.which("lspci") else None,
    "dpdk_version": command("pkg-config", "--modversion", "libdpdk") if shutil.which("pkg-config") else None,
    "environment": {key: os.environ[key] for key in sorted(os.environ) if key.startswith("WIREFIN_")},
}
server_summary = None
for line in reversed((args.result_dir / "server.log").read_text().splitlines()):
    if line.startswith("{"):
        try:
            server_summary = json.loads(line)
            break
        except json.JSONDecodeError:
            pass
document = {"metadata": metadata, "server_process_summary": server_summary, "results": results}
time_path = args.result_dir / "server-time.txt"
gc_path = args.result_dir / "gc.log"
generator_path = args.result_dir / "generator-metadata.txt"
document["process_evidence"] = {
    "gnu_time_verbose": time_path.read_text() if time_path.exists() else None,
    "gc_log_events": sum("[gc,start" in line for line in gc_path.read_text().splitlines()) if gc_path.exists() else None,
    "jfr_recording": str(args.result_dir / "server.jfr") if (args.result_dir / "server.jfr").exists() else None,
    "note": "CPU, GC, and allocation evidence covers the whole server process, not an individual workload row.",
}
document["generator_metadata"] = generator_path.read_text() if generator_path.exists() else None
args.output_json.write_text(json.dumps(document, indent=2, sort_keys=True) + "\n")

fields = ["backend", "workload", "flows", "payload_bytes", "seconds", "operations",
          "packets_per_second", "bits_per_second", "latency_median_us", "latency_p95_us", "latency_p99_us"]
with args.output_csv.open("w", newline="") as output:
    writer = csv.DictWriter(output, fieldnames=fields, extrasaction="ignore")
    writer.writeheader()
    writer.writerows(results)
