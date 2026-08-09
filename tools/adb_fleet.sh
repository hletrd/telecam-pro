#!/usr/bin/env bash
# Bring the wireless test fleet up on STABLE, predictable adb endpoints.
#
# Why this exists twice over:
#
# 1. Wireless debugging hands out a RANDOM port that rotates on every toggle and
#    reboot, so every session began by asking the operator for fresh ports. Each
#    device now runs adbd on a fixed 5555 (`adb tcpip 5555`), which survives until
#    the device reboots.
#
# 2. On this multi-homed Mac, `adb connect 172.30.x.x` returns "No route to host"
#    for devices it can reach perfectly well — two interfaces share the 172.30/17
#    subnet and adb picks wrong. ping and nc both succeed on either interface, so
#    it is adb's socket choice, not the network. Pointing adb at 127.0.0.1 sidesteps
#    the choice; the forwarder just shuttles bytes.
#
# PMA110 is reached over Tailscale, where that ambiguity does not arise, so it
# connects directly and needs no forwarder.
#
# Local ports encode the device's last octet (…112 -> 5512) so a serial in a log
# says which handset it was without a lookup table.
set -u

PROXY="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/adb_proxy.py"

# label            device-ip         local-port
FLEET=(
  "SM-S918N        172.30.50.112     5512"
  "TB331FC         172.30.50.161     5561"
  "TB336ZU         172.30.50.249     5549"
)
PMA110_DIRECT="100.125.100.120:5555"

pkill -f "adb_proxy.py" 2>/dev/null
sleep 1

for row in "${FLEET[@]}"; do
  set -- $row
  label=$1; ip=$2; lport=$3
  nohup python3 "$PROXY" "$ip" "$lport:5555" >/dev/null 2>&1 &
done
sleep 2

for row in "${FLEET[@]}"; do
  set -- $row
  label=$1; ip=$2; lport=$3
  adb connect "127.0.0.1:$lport" >/dev/null 2>&1
  printf '  %-10s 127.0.0.1:%-6s -> %s:5555\n' "$label" "$lport" "$ip"
done

adb connect "$PMA110_DIRECT" >/dev/null 2>&1
printf '  %-10s %s (Tailscale, direct)\n' "PMA110" "$PMA110_DIRECT"

echo
adb devices -l
