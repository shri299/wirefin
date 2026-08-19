#!/usr/bin/env python3
"""Summarize Wirefin JSONL traces without assuming packet-for-packet equivalence."""

import argparse
import collections
import json
from pathlib import Path


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("trace", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()

    events = []
    with args.trace.open(encoding="utf-8") as stream:
        for line_number, line in enumerate(stream, 1):
            try:
                events.append(json.loads(line))
            except json.JSONDecodeError as failure:
                raise SystemExit(f"{args.trace}:{line_number}: {failure}") from failure

    directions = collections.Counter(event["direction"] for event in events)
    states = []
    retransmissions = 0
    for event in events:
        if not states or states[-1] != event["state"]:
            states.append(event["state"])
        retransmissions = max(retransmissions, event.get("retransmissions", 0))

    summary = {
        "events": len(events),
        "connections": len({event["connection_id"] for event in events}),
        "rx_segments": directions["RX"],
        "tx_segments": directions["TX"],
        "rx_payload_bytes": sum(event["payload_bytes"] for event in events if event["direction"] == "RX"),
        "tx_payload_bytes": sum(event["payload_bytes"] for event in events if event["direction"] == "TX"),
        "maximum_observed_retransmissions": retransmissions,
        "maximum_observed_cwnd": max((event["cwnd"] for event in events), default=0),
        "maximum_observed_rto_nanos": max((event["rto_nanos"] for event in events), default=0),
        "observed_state_sequence": states,
    }
    rendered = json.dumps(summary, indent=2, sort_keys=True) + "\n"
    if args.output:
        args.output.write_text(rendered, encoding="utf-8")
    else:
        print(rendered, end="")


if __name__ == "__main__":
    main()
