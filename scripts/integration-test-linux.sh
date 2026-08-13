#!/usr/bin/env bash
set -euo pipefail

if [[ "$(uname -s)" != "Linux" ]]; then
  echo "This interoperability test requires Linux." >&2
  exit 77
fi

for command in java mvn curl ip tcpdump; do
  command -v "$command" >/dev/null || { echo "Missing required command: $command" >&2; exit 2; }
done

TUN_NAME="${WIREFIN_TUN:-wf-tun0}"
HOST_ADDRESS="${WIREFIN_HOST_ADDRESS:-10.77.0.1}"
STACK_ADDRESS="${WIREFIN_STACK_ADDRESS:-10.77.0.2}"
PORT="${WIREFIN_PORT:-18080}"
RUN_DIR="$(mktemp -d)"
STACK_PID=""
TCPDUMP_PID=""
FAILED_LINE=0

cleanup() {
  [[ -z "$STACK_PID" ]] || kill "$STACK_PID" 2>/dev/null || true
  [[ -z "$TCPDUMP_PID" ]] || sudo kill "$TCPDUMP_PID" 2>/dev/null || true
  sudo ip tuntap del dev "$TUN_NAME" mode tun 2>/dev/null || true
  rm -rf "$RUN_DIR"
}

finish() {
  local status=$?
  if (( status != 0 )); then
    echo "Linux interoperability test failed with status $status at line $FAILED_LINE." >&2
    [[ ! -f "$RUN_DIR/wirefin.log" ]] || { echo "--- wirefin.log ---" >&2; cat "$RUN_DIR/wirefin.log" >&2; }
    [[ ! -f "$RUN_DIR/tcpdump.log" ]] || { echo "--- tcpdump.log ---" >&2; cat "$RUN_DIR/tcpdump.log" >&2; }
    echo "--- TUN state ---" >&2
    ip address show dev "$TUN_NAME" >&2 || true
    if [[ -f "$RUN_DIR/wirefin.pcap" ]]; then
      echo "--- packet trace ---" >&2
      sudo tcpdump -nn -r "$RUN_DIR/wirefin.pcap" >&2 || true
    fi
  fi
  cleanup
  exit "$status"
}
trap finish EXIT
trap 'FAILED_LINE=$LINENO' ERR
trap 'exit 130' INT TERM

mvn --batch-mode clean package
sudo modprobe tun 2>/dev/null || true
sudo ip tuntap add dev "$TUN_NAME" mode tun user "$USER"
sudo ip address add "$HOST_ADDRESS/30" dev "$TUN_NAME"
sudo ip link set dev "$TUN_NAME" up

sudo tcpdump -U -i "$TUN_NAME" -nn -s 0 -w "$RUN_DIR/wirefin.pcap" "tcp port $PORT" \
  >"$RUN_DIR/tcpdump.log" 2>&1 &
TCPDUMP_PID=$!

for _ in {1..50}; do
  grep -q "listening on $TUN_NAME" "$RUN_DIR/tcpdump.log" && break
  kill -0 "$TCPDUMP_PID" 2>/dev/null || { cat "$RUN_DIR/tcpdump.log" >&2; exit 1; }
  sleep 0.1
done
grep -q "listening on $TUN_NAME" "$RUN_DIR/tcpdump.log"

java -jar target/wirefin-0.1.0-SNAPSHOT-all.jar \
  --tun "$TUN_NAME" --address "$STACK_ADDRESS" --port "$PORT" --debug \
  >"$RUN_DIR/wirefin.log" 2>&1 &
STACK_PID=$!

for _ in {1..50}; do
  grep -q "Wirefin listening" "$RUN_DIR/wirefin.log" && break
  kill -0 "$STACK_PID" 2>/dev/null || { cat "$RUN_DIR/wirefin.log" >&2; exit 1; }
  sleep 0.1
done
grep -q "Wirefin listening" "$RUN_DIR/wirefin.log"

BODY="$(curl --fail --silent --show-error --http1.1 --max-time 5 "http://$STACK_ADDRESS:$PORT/")"
[[ "$BODY" == "Hello from userspace TCP" ]] || { echo "Unexpected response: $BODY" >&2; exit 1; }

sleep 1
sudo kill -INT "$TCPDUMP_PID" 2>/dev/null || true
wait "$TCPDUMP_PID" 2>/dev/null || true
TCPDUMP_PID=""
TRACE="$(tcpdump -nn -r "$RUN_DIR/wirefin.pcap" 2>/dev/null)"
grep -q 'Flags \[S\]' <<<"$TRACE"
grep -q 'Flags \[S\.\]' <<<"$TRACE"
grep -q 'Flags \[F\.\]' <<<"$TRACE"

echo "Linux kernel TCP ↔ Wirefin interoperability passed."
echo "$TRACE"
