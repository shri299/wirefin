#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/../.." && pwd)"
source "$SCRIPT_DIR/common.sh"
wf_require_linux

: "${DPDK_BDF:?set DPDK_BDF to the explicitly selected PCI BDF}"
: "${DPDK_LOCAL_MAC:?set DPDK_LOCAL_MAC}"
: "${DPDK_PEER_MAC:?set DPDK_PEER_MAC}"
wf_validate_bdf "$DPDK_BDF"
"$SCRIPT_DIR/validate.sh" --bdf "$DPDK_BDF"

cd "$REPO_ROOT"
make -C native/dpdk all
mvn --batch-mode package
export LD_LIBRARY_PATH="$REPO_ROOT/native/dpdk${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
EAL_ARGS="${DPDK_EAL_ARGS:-wirefin -l 1-2 --main-lcore 1}"
exec java ${WIREFIN_JVM_ARGS:-} -cp target/wirefin-0.1.0-SNAPSHOT-all.jar \
  io.github.shri299.wirefin.examples.PerformanceServer \
  --backend dpdk --address "${WIREFIN_STACK:-192.0.2.2}" --address6 "${WIREFIN_STACK6:-2001:db8::2}" \
  --local-mac "$DPDK_LOCAL_MAC" --peer-mac "$DPDK_PEER_MAC" \
  --port-id "${DPDK_PORT_ID:-0}" --batch "${WIREFIN_BATCH:-32}" --duration "${WIREFIN_DURATION:-60}" \
  --eal-args "$EAL_ARGS"
