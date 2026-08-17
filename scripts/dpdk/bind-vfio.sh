#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/common.sh"
wf_require_linux
wf_require_root
wf_require_command ip
wf_require_command modprobe

BDF=""; FORCE_DEFAULT=0
while (($#)); do
  case "$1" in
    --bdf) BDF="${2:-}"; shift 2 ;;
    --force-default-route) FORCE_DEFAULT=1; shift ;;
    *) wf_die "usage: $0 --bdf DDDD:BB:SS.F [--force-default-route]" ;;
  esac
done
wf_validate_bdf "$BDF"
DEVICE="/sys/bus/pci/devices/$BDF"
[[ -d "$DEVICE" ]] || wf_die "PCI device does not exist: $BDF"

mapfile -t INTERFACES < <(wf_bdf_interfaces "$BDF")
mapfile -t DEFAULTS < <(wf_default_route_interfaces)
for interface in "${INTERFACES[@]}"; do
  for default_interface in "${DEFAULTS[@]}"; do
    if [[ "$interface" == "$default_interface" && "$FORCE_DEFAULT" -ne 1 ]]; then
      wf_die "$BDF owns default-route interface $interface; use a dedicated NIC or explicitly pass --force-default-route with out-of-band access"
    fi
  done
done

DRIVER=""
[[ -L "$DEVICE/driver" ]] && DRIVER="$(basename "$(readlink -f "$DEVICE/driver")")"
[[ -n "$DRIVER" ]] || wf_die "cannot determine the current driver for $BDF"
[[ "$DRIVER" != vfio-pci ]] || wf_die "$BDF is already bound to vfio-pci"

STATE_DIR="$(wf_state_dir)"; mkdir -p -- "$STATE_DIR"
STATE_FILE="$STATE_DIR/${BDF//[:.]/_}.state"
[[ ! -e "$STATE_FILE" ]] || wf_die "restore state already exists: $STATE_FILE"
{
  printf 'BDF=%s\n' "$BDF"
  printf 'DRIVER=%s\n' "$DRIVER"
  printf 'INTERFACES=%s\n' "${INTERFACES[*]:-}"
} >"$STATE_FILE"
chmod 600 "$STATE_FILE"

for interface in "${INTERFACES[@]}"; do ip link set dev "$interface" down; done
modprobe vfio-pci
"$(wf_devbind)" --bind=vfio-pci "$BDF" || {
  wf_note "binding failed; attempting immediate restoration to $DRIVER"
  "$(wf_devbind)" --bind="$DRIVER" "$BDF" || true
  exit 1
}
[[ "$(basename "$(readlink -f "$DEVICE/driver")")" == vfio-pci ]] || wf_die "binding command completed but vfio-pci is not active"
wf_note "bound $BDF to vfio-pci; restore metadata: $STATE_FILE"
