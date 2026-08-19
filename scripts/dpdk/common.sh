#!/usr/bin/env bash

wf_die() { echo "wirefin-dpdk: $*" >&2; exit 1; }
wf_note() { echo "wirefin-dpdk: $*" >&2; }
wf_require_linux() { [[ "$(uname -s)" == Linux ]] || wf_die "Linux is required"; }
wf_require_root() { [[ "${EUID:-$(id -u)}" -eq 0 ]] || wf_die "run as root (sudo -E ...)"; }
wf_require_command() { command -v "$1" >/dev/null 2>&1 || wf_die "missing required command: $1"; }
wf_validate_bdf() { [[ "$1" =~ ^[[:xdigit:]]{4}:[[:xdigit:]]{2}:[[:xdigit:]]{2}\.[0-7]$ ]] || wf_die "invalid PCI BDF: $1"; }

wf_devbind() {
  local candidate
  for candidate in "${DPDK_DEVBIND:-}" /usr/bin/dpdk-devbind.py /usr/local/bin/dpdk-devbind.py /usr/share/dpdk/usertools/dpdk-devbind.py; do
    [[ -n "$candidate" && -x "$candidate" ]] && { echo "$candidate"; return; }
  done
  wf_die "dpdk-devbind.py not found; set DPDK_DEVBIND"
}

wf_default_route_interfaces() {
  ip -o route show default 2>/dev/null | awk '{for(i=1;i<=NF;i++) if($i=="dev") print $(i+1)}' | sort -u
}

wf_interface_bdf() {
  local interface="$1" device
  device="$(readlink -f "/sys/class/net/$interface/device" 2>/dev/null || true)"
  [[ -n "$device" ]] && basename "$device"
}

wf_bdf_interfaces() {
  local bdf="$1" path
  for path in "/sys/bus/pci/devices/$bdf"/net/*; do
    [[ -e "$path" ]] && basename "$path"
  done
}

wf_state_dir() { echo "${WIREFIN_DPDK_STATE_DIR:-target/dpdk-state}"; }
