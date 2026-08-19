#!/usr/bin/env python3
"""Advisory comparison of two explicit Wirefin benchmark documents."""

import argparse
import json


parser = argparse.ArgumentParser()
parser.add_argument("baseline")
parser.add_argument("candidate")
parser.add_argument("--throughput-threshold", type=float, default=5.0)
parser.add_argument("--allocation-threshold", type=float, default=10.0)
parser.add_argument("--p99-threshold", type=float, default=10.0)
parser.add_argument("--fail", action="store_true", help="exit nonzero on regression; off by default")
args = parser.parse_args()


def load(path):
    document = json.load(open(path, encoding="utf-8"))
    rows = document.get("results", document)
    return {(row.get("backend"), row["workload"], row["flows"], row["payload_bytes"]): row for row in rows}


baseline, candidate = load(args.baseline), load(args.candidate)
regressions = []
for key in sorted(baseline.keys() & candidate.keys()):
    old, new = baseline[key], candidate[key]
    checks = [
        ("packets_per_second", args.throughput_threshold, "lower"),
        ("allocation_bytes_per_operation", args.allocation_threshold, "higher"),
        ("latency_p99_us", args.p99_threshold, "higher"),
    ]
    for metric, threshold, direction in checks:
        if old.get(metric) in (None, 0) or new.get(metric) is None:
            continue
        change = 100.0 * (new[metric] - old[metric]) / old[metric]
        regressed = change < -threshold if direction == "lower" else change > threshold
        marker = "REGRESSION" if regressed else "ok"
        print(f"{marker:10} {key} {metric}: {change:+.2f}%")
        if regressed:
            regressions.append((key, metric, change))
missing = baseline.keys() - candidate.keys()
for key in sorted(missing):
    print(f"missing    {key}")
if regressions and args.fail:
    raise SystemExit(1)
