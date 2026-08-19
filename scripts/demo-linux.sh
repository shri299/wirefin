#!/usr/bin/env bash
set -euo pipefail

RESULT_DIR="${1:-target/wirefin-demo}"
./scripts/netem-lab.sh loss-5 "$RESULT_DIR"
python3 - "$RESULT_DIR/loss-5/wirefin" <<'PY'
import json, pathlib, sys
root=pathlib.Path(sys.argv[1])
curl=json.loads((root/"curl.json").read_text())
summary=json.loads((root/"trace-summary.json").read_text())
print("\nApplication result")
print(json.dumps(curl,indent=2,sort_keys=True))
print("\nTCP semantic summary")
print(json.dumps(summary,indent=2,sort_keys=True))
print("\nSelected TCB transitions")
previous=None
for line in (root/"trace.jsonl").read_text().splitlines():
    event=json.loads(line)
    current=(event["state"],event["cwnd"],event["rto_nanos"],event["retransmissions"])
    if current != previous:
        print(f'{event["direction"]:2} state={event["state"]:12} seq={event["seq"]:10} '
              f'ack={event["ack"]:10} cwnd={event["cwnd"]:6} '
              f'rto_ms={event["rto_nanos"]/1_000_000:8.3f} rtx={event["retransmissions"]}')
        previous=current
if not summary["maximum_observed_retransmissions"]:
    print("\nNo Wirefin retransmission was observed in this randomized short flow; do not claim recovery from this run.")
print(f"\nOpen {root/'session.pcapng'} in Wireshark for the raw-IP RX/TX trace.")
PY
