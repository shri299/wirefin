#!/usr/bin/env bash
set -euo pipefail

[[ "$(uname -s)" == Linux ]] || { echo "Linux required" >&2; exit 77; }
for command in git java mvn ip python3; do
  command -v "$command" >/dev/null || { echo "Missing required command: $command" >&2; exit 2; }
done

BACKEND="${WIREFIN_BACKEND:-tun}"
RESULT_DIR="${1:-target/linux-benchmark}"
DURATION="${WIREFIN_DURATION:-10}"
BATCH="${WIREFIN_BATCH:-32}"
FLOWS="${WIREFIN_FLOWS:-1 10 100}"
TUN="${WIREFIN_TUN:-wf-perf0}"
HOST="${WIREFIN_HOST:-10.77.0.1}"
STACK="${WIREFIN_STACK:-10.77.0.2}"
STACK6="${WIREFIN_STACK6:-fd00:77::2}"
TCP_PORT="${WIREFIN_TCP_PORT:-19080}"
UDP_PORT="${WIREFIN_UDP_PORT:-19081}"
GENERATOR_SSH="${WIREFIN_GENERATOR_SSH:-}"
GENERATOR_DIR="${WIREFIN_GENERATOR_DIR:-wirefin}"
SERVER_PID=""

read -r -a FLOW_COUNTS <<<"$FLOWS"
for flow_count in "${FLOW_COUNTS[@]}"; do
  [[ "$flow_count" =~ ^[1-9][0-9]*$ ]] || { echo "Invalid flow count: $flow_count" >&2; exit 2; }
done
[[ "$BACKEND" == tun || "$BACKEND" == dpdk ]] || { echo "WIREFIN_BACKEND must be tun or dpdk" >&2; exit 2; }
if [[ "$BACKEND" == dpdk && -z "$GENERATOR_SSH" ]]; then
  echo "DPDK requires WIREFIN_GENERATOR_SSH for an external peer; local kernel sockets cannot exercise a VFIO-bound NIC." >&2
  exit 2
fi
if [[ "$BACKEND" == tun ]] && ip link show dev "$TUN" >/dev/null 2>&1; then
  echo "Refusing to reuse existing interface $TUN." >&2
  exit 2
fi

mkdir -p "$RESULT_DIR/results"
if [[ -n "$GENERATOR_SSH" ]]; then
  command -v ssh >/dev/null || { echo "Missing required command: ssh" >&2; exit 2; }
  ssh "$GENERATOR_SSH" "uname -a; lscpu; ip -brief link" >"$RESULT_DIR/generator-metadata.txt"
fi
cleanup() {
  [[ -z "$SERVER_PID" ]] || kill "$SERVER_PID" 2>/dev/null || true
  [[ "$BACKEND" != tun ]] || sudo ip tuntap del dev "$TUN" mode tun 2>/dev/null || true
}
trap cleanup EXIT INT TERM

mvn --batch-mode -DskipTests package
if [[ "$BACKEND" == tun ]]; then
  sudo modprobe tun 2>/dev/null || true
  sudo ip tuntap add dev "$TUN" mode tun user "$USER"
  sudo ip address add "$HOST/30" dev "$TUN"
  sudo ip link set "$TUN" up
fi

SERVER_ARGS=(--backend "$BACKEND" --address "$STACK" --address6 "$STACK6" --tcp-port "$TCP_PORT" --udp-port "$UDP_PORT" --batch "$BATCH")
if [[ "$BACKEND" == tun ]]; then
  SERVER_ARGS+=(--tun "$TUN")
else
  SERVER_ARGS+=(--port-id "${DPDK_PORT_ID:?Set DPDK_PORT_ID}" --local-mac "${DPDK_LOCAL_MAC:?Set DPDK_LOCAL_MAC}" --peer-mac "${DPDK_PEER_MAC:?Set DPDK_PEER_MAC}" --eal-args "${DPDK_EAL_ARGS:-wirefin -l 1-2}")
fi
RUN_COUNT="$((5 * ${#FLOW_COUNTS[@]}))"
SERVER_DURATION="$((RUN_COUNT * DURATION + 20))"
SERVER_ARGS+=(--duration "$SERVER_DURATION")
PIN=()
if command -v taskset >/dev/null && [[ -n "${WIREFIN_CPU:-}" ]]; then PIN=(taskset -c "$WIREFIN_CPU"); fi
JVM_ARGS=(${WIREFIN_JVM_ARGS:--Xms1g -Xmx1g -XX:+AlwaysPreTouch})
JVM_ARGS+=("-Xlog:gc*:file=$RESULT_DIR/gc.log:time,uptime,level,tags")
if command -v jfr >/dev/null; then
  JVM_ARGS+=("-XX:StartFlightRecording=filename=$RESULT_DIR/server.jfr,settings=profile,dumponexit=true")
fi
/usr/bin/time -v -o "$RESULT_DIR/server-time.txt" "${PIN[@]}" java "${JVM_ARGS[@]}" \
  -cp target/wirefin-0.1.0-SNAPSHOT-all.jar io.github.shri299.wirefin.examples.PerformanceServer \
  "${SERVER_ARGS[@]}" >"$RESULT_DIR/server.log" 2>&1 &
SERVER_PID=$!
for _ in {1..200}; do
  grep -q 'performance server ready' "$RESULT_DIR/server.log" && break
  kill -0 "$SERVER_PID" 2>/dev/null || { cat "$RESULT_DIR/server.log" >&2; exit 1; }
  sleep 0.1
done
grep -q 'performance server ready' "$RESULT_DIR/server.log"

run_case() {
  local workload="$1" payload="$2" flows="$3" name="$4" port="$TCP_PORT"
  [[ "$workload" != udp ]] || port="$UDP_PORT"
  if [[ -n "$GENERATOR_SSH" ]]; then
    ssh "$GENERATOR_SSH" python3 "$GENERATOR_DIR/scripts/benchmark-linux.py" "$workload" "$STACK" "$port" \
      --seconds "$DURATION" --payload "$payload" --flows "$flows" >"$RESULT_DIR/results/${name}-flows-${flows}.json"
  else
    python3 scripts/benchmark-linux.py "$workload" "$STACK" "$port" --seconds "$DURATION" \
      --payload "$payload" --flows "$flows" >"$RESULT_DIR/results/${name}-flows-${flows}.json"
  fi
}
for flows in "${FLOW_COUNTS[@]}"; do
  run_case small-tcp 64 "$flows" tcp-connect-tiny
  run_case stream 64 "$flows" tcp-stream-tiny
  run_case stream 1460 "$flows" tcp-stream-mss
  run_case udp 64 "$flows" udp-tiny
  run_case udp 1400 "$flows" udp-full
done

wait "$SERVER_PID"
SERVER_PID=""
python3 scripts/benchmark-report.py "$RESULT_DIR" --backend "$BACKEND" --batch "$BATCH" \
  --jvm-flags "${JVM_ARGS[*]}" --output-json "$RESULT_DIR/benchmark.json" --output-csv "$RESULT_DIR/scalability.csv"
echo "Results written to $RESULT_DIR"
