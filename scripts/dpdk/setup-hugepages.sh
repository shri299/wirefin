#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/common.sh"
wf_require_linux
wf_require_root

PAGES=""; NODE=""; MOUNT_POINT=""
while (($#)); do
  case "$1" in
    --pages) PAGES="${2:-}"; shift 2 ;;
    --node) NODE="${2:-}"; shift 2 ;;
    --mount) MOUNT_POINT="${2:-}"; shift 2 ;;
    *) wf_die "usage: $0 --pages COUNT [--node NUMA_NODE] [--mount PATH]" ;;
  esac
done
[[ "$PAGES" =~ ^[1-9][0-9]*$ ]] || wf_die "--pages must be a positive integer"

if [[ -n "$NODE" ]]; then
  [[ "$NODE" =~ ^[0-9]+$ ]] || wf_die "--node must be a non-negative integer"
  TARGET="/sys/devices/system/node/node$NODE/hugepages/hugepages-2048kB/nr_hugepages"
  [[ -w "$TARGET" ]] || wf_die "NUMA hugepage control is unavailable or not writable: $TARGET"
  printf '%s\n' "$PAGES" >"$TARGET"
  ACTUAL="$(<"$TARGET")"
else
  wf_require_command sysctl
  sysctl -w "vm.nr_hugepages=$PAGES" >/dev/null
  ACTUAL="$(sysctl -n vm.nr_hugepages)"
fi
[[ "$ACTUAL" -ge "$PAGES" ]] || wf_die "requested $PAGES hugepages but only $ACTUAL are available"

if [[ -n "$MOUNT_POINT" ]]; then
  wf_require_command mountpoint
  mkdir -p -- "$MOUNT_POINT"
  mountpoint -q "$MOUNT_POINT" || mount -t hugetlbfs nodev "$MOUNT_POINT"
fi
wf_note "configured $ACTUAL 2 MiB hugepages${NODE:+ on NUMA node $NODE}"
