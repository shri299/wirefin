#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/common.sh"
wf_require_linux
wf_require_command lspci
wf_require_command ip

echo "Ethernet-class PCI devices (no changes are made):"
lspci -Dnnk | awk '
  /^[[:xdigit:]]{4}:[[:xdigit:]]{2}:[[:xdigit:]]{2}\.[0-7].*(Ethernet|Network controller)/ {show=1}
  show {print}
  show && /^$/ {show=0}
'
echo
echo "Default-route interfaces (do not bind these without an out-of-band management path):"
while IFS= read -r interface; do
  [[ -n "$interface" ]] || continue
  printf '  %-16s PCI=%s\n' "$interface" "$(wf_interface_bdf "$interface")"
done < <(wf_default_route_interfaces)
echo
"$(wf_devbind)" --status
