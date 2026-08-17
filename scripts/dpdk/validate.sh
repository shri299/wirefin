#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/common.sh"
wf_require_linux

BDF="${2:-}"
[[ "${1:-}" == --bdf ]] || wf_die "usage: $0 --bdf DDDD:BB:SS.F"
wf_validate_bdf "$BDF"
DEVICE="/sys/bus/pci/devices/$BDF"
[[ -d "$DEVICE" ]] || wf_die "PCI device does not exist: $BDF"

FAILURES=0
check() { if "$@"; then printf 'ok   %s\n' "$*"; else printf 'FAIL %s\n' "$*"; FAILURES=$((FAILURES+1)); fi; }
check test -x "$(wf_devbind)"
check command -v java
check command -v pkg-config
check pkg-config --exists libdpdk
check test -d /sys/kernel/iommu_groups
check test -e "$DEVICE/iommu_group"
check test "$(grep -E '^HugePages_Total:' /proc/meminfo | awk '{print $2}')" -gt 0
check test "$(basename "$(readlink -f "$DEVICE/driver" 2>/dev/null || echo none)")" = vfio-pci

echo "NUMA node: $(<"$DEVICE/numa_node")"
echo "Hugepages: $(grep -E '^HugePages_(Total|Free|Rsvd|Surp):' /proc/meminfo | tr '\n' ' ')"
echo "DPDK: $(pkg-config --modversion libdpdk 2>/dev/null || echo unavailable)"
((FAILURES == 0)) || wf_die "$FAILURES validation check(s) failed"
wf_note "environment validation passed for $BDF"
