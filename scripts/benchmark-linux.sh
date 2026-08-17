#!/usr/bin/env bash
set -euo pipefail
[[ "$(uname -s)" == Linux ]] || { echo "Linux required" >&2; exit 77; }
for command in java mvn ip python3;do command -v "$command" >/dev/null||{ echo "missing $command" >&2;exit 2;};done
BACKEND="${WIREFIN_BACKEND:-tun}";RESULT_DIR="${1:-target/linux-benchmark}";DURATION="${WIREFIN_DURATION:-10}";BATCH="${WIREFIN_BATCH:-32}"
TUN="${WIREFIN_TUN:-wf-perf0}";HOST="${WIREFIN_HOST:-10.77.0.1}";STACK="${WIREFIN_STACK:-10.77.0.2}";HOST6="${WIREFIN_HOST6:-fd00:77::1}";STACK6="${WIREFIN_STACK6:-fd00:77::2}"
TCP_PORT="${WIREFIN_TCP_PORT:-19080}";UDP_PORT="${WIREFIN_UDP_PORT:-19081}";mkdir -p "$RESULT_DIR";SERVER_PID=""
cleanup(){ [[ -z "$SERVER_PID" ]]||kill "$SERVER_PID" 2>/dev/null||true;[[ "$BACKEND" != tun ]]||sudo ip tuntap del dev "$TUN" mode tun 2>/dev/null||true;};trap cleanup EXIT
mvn --batch-mode clean package
if [[ "$BACKEND" == tun ]];then sudo modprobe tun 2>/dev/null||true;sudo ip tuntap add dev "$TUN" mode tun user "$USER";sudo ip addr add "$HOST/30" dev "$TUN";sudo ip -6 addr add "$HOST6/64" dev "$TUN";sudo ip link set "$TUN" up;fi
SERVER_ARGS=(--backend "$BACKEND" --address "$STACK" --address6 "$STACK6" --tcp-port "$TCP_PORT" --udp-port "$UDP_PORT" --duration "$((4*DURATION+10))" --batch "$BATCH")
if [[ "$BACKEND" == tun ]];then SERVER_ARGS+=(--tun "$TUN");else SERVER_ARGS+=(--port-id "${DPDK_PORT_ID:?}" --local-mac "${DPDK_LOCAL_MAC:?}" --peer-mac "${DPDK_PEER_MAC:?}" --eal-args "${DPDK_EAL_ARGS:-wirefin -l 1-2}");fi
PIN=();command -v taskset >/dev/null&&[[ -n "${WIREFIN_CPU:-}" ]]&&PIN=(taskset -c "$WIREFIN_CPU")
/usr/bin/time -v -o "$RESULT_DIR/server-time.txt" "${PIN[@]}" java -cp target/wirefin-0.1.0-SNAPSHOT-all.jar io.github.shri299.wirefin.examples.PerformanceServer "${SERVER_ARGS[@]}" >"$RESULT_DIR/server.log" 2>&1 &SERVER_PID=$!
for _ in {1..100};do grep -q 'performance server ready' "$RESULT_DIR/server.log"&&break;sleep .1;done;grep -q 'performance server ready' "$RESULT_DIR/server.log"
python3 scripts/benchmark-linux.py small-tcp "$STACK" "$TCP_PORT" --seconds "$DURATION" --payload 64 >"$RESULT_DIR/small-tcp.json"
python3 scripts/benchmark-linux.py stream "$STACK" "$TCP_PORT" --seconds "$DURATION" --payload 1460 >"$RESULT_DIR/stream.json"
python3 scripts/benchmark-linux.py udp "$STACK" "$UDP_PORT" --seconds "$DURATION" --payload 64 >"$RESULT_DIR/udp.json"
python3 scripts/benchmark-linux.py flows "$STACK" "$TCP_PORT" --seconds "$DURATION" --payload 1460 --flows 32 >"$RESULT_DIR/flows.json"
wait "$SERVER_PID"||true;SERVER_PID="";uname -a >"$RESULT_DIR/environment.txt";lscpu >>"$RESULT_DIR/environment.txt";java -version >>"$RESULT_DIR/environment.txt" 2>&1
echo "Results written to $RESULT_DIR"
