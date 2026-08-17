#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/common.sh"
wf_require_linux
wf_require_root
wf_require_command ip

BDF="${2:-}"
[[ "${1:-}" == --bdf ]] || wf_die "usage: $0 --bdf DDDD:BB:SS.F"
wf_validate_bdf "$BDF"
STATE_FILE="$(wf_state_dir)/${BDF//[:.]/_}.state"
[[ -r "$STATE_FILE" ]] || wf_die "restore metadata not found: $STATE_FILE"

SAVED_BDF="$(awk -F= '$1=="BDF" {print $2}' "$STATE_FILE")"
DRIVER="$(awk -F= '$1=="DRIVER" {print $2}' "$STATE_FILE")"
INTERFACES="$(awk -F= '$1=="INTERFACES" {sub(/^[^=]*=/,""); print}' "$STATE_FILE")"
[[ "$SAVED_BDF" == "$BDF" && "$DRIVER" =~ ^[A-Za-z0-9_-]+$ ]] || wf_die "invalid restore metadata"

"$(wf_devbind)" --bind="$DRIVER" "$BDF"
for interface in $INTERFACES; do
  [[ "$interface" =~ ^[A-Za-z0-9_.:-]+$ ]] || wf_die "invalid interface in restore metadata"
  [[ -e "/sys/class/net/$interface" ]] && ip link set dev "$interface" up
done
rm -- "$STATE_FILE"
wf_note "restored $BDF to $DRIVER; IP addresses and routes may need restoration by the host network manager"
