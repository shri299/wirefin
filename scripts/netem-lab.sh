#!/usr/bin/env bash
set -euo pipefail

[[ "$(uname -s)" == "Linux" ]] || { echo "This lab requires Linux." >&2; exit 77; }
for command in curl ip java mvn python3 tc; do
  command -v "$command" >/dev/null || { echo "Missing required command: $command" >&2; exit 2; }
done

SCENARIO="${1:-all}"
RESULT_ROOT="${2:-target/netem-lab}"
TUN="${WIREFIN_TUN:-wf-netem0}"
HOST="${WIREFIN_HOST:-10.78.0.1}"
STACK="${WIREFIN_STACK:-10.78.0.2}"
PORT="${WIREFIN_PORT:-18080}"
CONGESTION_CONTROL="${WIREFIN_CONGESTION_CONTROL:-reno}"
REFERENCE="${WIREFIN_REFERENCE:-true}"
RUN_ID="$$"
CLIENT_NS="wf-c-${RUN_ID}"
SERVER_NS="wf-s-${RUN_ID}"
CLIENT_VETH="wfc${RUN_ID: -5}"
SERVER_VETH="wfs${RUN_ID: -5}"
SERVER_PID=""

declare -A NETEM=(
  [clean]=""
  [loss-0.1]="loss 0.1%"
  [loss-1]="loss 1%"
  [loss-5]="loss 5%"
  [rtt-10]="delay 5ms"
  [rtt-50]="delay 25ms"
  [rtt-100]="delay 50ms"
  [jitter]="delay 25ms 10ms distribution normal"
  [duplicate]="duplicate 1%"
  [reorder]="delay 10ms reorder 10% 50%"
  [bandwidth-1mbit]="rate 1mbit"
)

cleanup_server() {
  [[ -z "$SERVER_PID" ]] || kill "$SERVER_PID" 2>/dev/null || true
  SERVER_PID=""
  sudo tc qdisc del dev "$TUN" root 2>/dev/null || true
}

cleanup_all() {
  cleanup_server
  sudo ip tuntap del dev "$TUN" mode tun 2>/dev/null || true
  sudo ip netns del "$CLIENT_NS" 2>/dev/null || true
  sudo ip netns del "$SERVER_NS" 2>/dev/null || true
}
trap cleanup_all EXIT INT TERM

if ip link show dev "$TUN" >/dev/null 2>&1; then
  echo "Refusing to reuse existing interface $TUN; set WIREFIN_TUN to an unused name." >&2
  exit 2
fi
if [[ "$SCENARIO" != all && -z "${NETEM[$SCENARIO]+present}" ]]; then
  echo "Unknown scenario '$SCENARIO'. Valid values: all ${!NETEM[*]}" >&2
  exit 2
fi

mkdir -p "$RESULT_ROOT"
mvn --batch-mode -DskipTests package
sudo modprobe tun 2>/dev/null || true
sudo ip tuntap add dev "$TUN" mode tun user "$USER"
sudo ip address add "$HOST/30" dev "$TUN"
sudo ip link set "$TUN" up

run_curl() {
  local url="$1" body_file="$2" result_file="$3"
  curl --fail --silent --show-error --http1.1 --max-time 30 \
    --output "$body_file" --write-out \
    '{"http_code":%{http_code},"time_connect_seconds":%{time_connect},"time_starttransfer_seconds":%{time_starttransfer},"time_total_seconds":%{time_total},"download_bytes":%{size_download}}\n' \
    "$url" >"$result_file"
}

run_wirefin() {
  local name="$1" rule="$2" output="$RESULT_ROOT/$name/wirefin"
  mkdir -p "$output"
  sudo tc qdisc replace dev "$TUN" root netem $rule
  java -jar target/wirefin-0.1.0-SNAPSHOT-all.jar --tun "$TUN" --address "$STACK" --port "$PORT" \
    --congestion-control "$CONGESTION_CONTROL" --pcap "$output/session.pcapng" \
    --trace "$output/trace.jsonl" >"$output/server.log" 2>&1 &
  SERVER_PID=$!
  for _ in {1..100}; do
    grep -q "Wirefin listening" "$output/server.log" && break
    kill -0 "$SERVER_PID" 2>/dev/null || { cat "$output/server.log" >&2; return 1; }
    sleep 0.1
  done
  grep -q "Wirefin listening" "$output/server.log"
  run_curl "http://$STACK:$PORT/" "$output/body.txt" "$output/curl.json"
  grep -qx "Hello from userspace TCP" "$output/body.txt"
  cleanup_server
  python3 scripts/summarize-trace.py "$output/trace.jsonl" --output "$output/trace-summary.json"
}

prepare_reference() {
  sudo ip netns add "$CLIENT_NS"
  sudo ip netns add "$SERVER_NS"
  sudo ip link add "$CLIENT_VETH" type veth peer name "$SERVER_VETH"
  sudo ip link set "$CLIENT_VETH" netns "$CLIENT_NS"
  sudo ip link set "$SERVER_VETH" netns "$SERVER_NS"
  sudo ip -n "$CLIENT_NS" address add 10.79.0.1/30 dev "$CLIENT_VETH"
  sudo ip -n "$SERVER_NS" address add 10.79.0.2/30 dev "$SERVER_VETH"
  sudo ip -n "$CLIENT_NS" link set lo up
  sudo ip -n "$SERVER_NS" link set lo up
  sudo ip -n "$CLIENT_NS" link set "$CLIENT_VETH" up
  sudo ip -n "$SERVER_NS" link set "$SERVER_VETH" up
}

run_reference() {
  local name="$1" rule="$2" output="$RESULT_ROOT/$name/linux-reference"
  mkdir -p "$output/www"
  printf 'Hello from Linux TCP\n' >"$output/www/index.html"
  sudo ip netns exec "$CLIENT_NS" tc qdisc replace dev "$CLIENT_VETH" root netem $rule
  sudo ip netns exec "$SERVER_NS" python3 -m http.server "$PORT" --bind 10.79.0.2 --directory "$PWD/$output/www" \
    >"$output/server.log" 2>&1 &
  SERVER_PID=$!
  sleep 0.3
  sudo ip netns exec "$CLIENT_NS" curl --fail --silent --show-error --http1.1 --max-time 30 \
    --output "$PWD/$output/body.txt" --write-out \
    '{"http_code":%{http_code},"time_connect_seconds":%{time_connect},"time_starttransfer_seconds":%{time_starttransfer},"time_total_seconds":%{time_total},"download_bytes":%{size_download}}\n' \
    "http://10.79.0.2:$PORT/" >"$output/curl.json"
  grep -qx "Hello from Linux TCP" "$output/body.txt"
  cleanup_server
}

if [[ "$REFERENCE" == true ]]; then prepare_reference; fi
if [[ "$SCENARIO" == all ]]; then
  SCENARIOS=(clean loss-0.1 loss-1 loss-5 rtt-10 rtt-50 rtt-100 jitter duplicate reorder bandwidth-1mbit)
else
  SCENARIOS=("$SCENARIO")
fi
for name in "${SCENARIOS[@]}"; do
  echo "Running $name: netem ${NETEM[$name]:-(no impairment)}"
  run_wirefin "$name" "${NETEM[$name]}"
  if [[ "$REFERENCE" == true ]]; then run_reference "$name" "${NETEM[$name]}"; fi
done
python3 - "$RESULT_ROOT" <<'PY'
import json, pathlib, sys
root=pathlib.Path(sys.argv[1]); rows=[]
for scenario in sorted(p for p in root.iterdir() if p.is_dir()):
    row={"scenario":scenario.name}
    for implementation in ("wirefin","linux-reference"):
        path=scenario/implementation/"curl.json"
        if path.exists(): row[implementation]=json.loads(path.read_text())
    rows.append(row)
(root/"summary.json").write_text(json.dumps(rows,indent=2,sort_keys=True)+"\n")
PY
echo "Netem lab completed; evidence is in $RESULT_ROOT."
