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

TOOLS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$TOOLS_DIR/.." && pwd)"
PROXY="$TOOLS_DIR/adb_proxy.py"
STATE_DIR="${ADB_FLEET_STATE_DIR:-$ROOT/.tool-state/adb-fleet}"
ACTION="${1:-start}"

# Local ports are 6000 + the device's last octet, so a serial in a log says which
# handset it was without a lookup table.
#
# 6xxx, NOT 5xxx: adb reserves 5554-5585 for emulator consoles and labels anything
# on those ports `emulator-NNNN`. The first cut of this table used 5561 for
# TB331FC, and adb duly reported a Lenovo tablet as "emulator-5560" — which breaks
# every `adb -s` invocation that expects the assigned serial. 6000+ is clear of it.
#
# label            device-ip         local-port
FLEET=(
  "SM-S918N        172.30.50.112     6112"
  "POCO            172.30.50.114     6114"
  "TB331FC         172.30.50.161     6161"
  "TB336ZU         172.30.50.249     6249"
)
PMA110_DIRECT="100.125.100.120:5555"

record_path() {
  printf '%s/%s.pid' "$STATE_DIR" "$1"
}

read_record() {
  record=$1
  IFS='|' read -r OWNED_PID OWNED_TOKEN OWNED_PROXY OWNED_HOST OWNED_SPEC < "$record" || return 1
  case "$OWNED_PID" in ''|*[!0-9]*) return 1 ;; esac
  [ -n "$OWNED_TOKEN" ] && [ "$OWNED_PROXY" = "$PROXY" ]
}

owned_proxy_alive() {
  record=$1; expected_host=$2; expected_spec=$3
  [ -f "$record" ] || return 1
  read_record "$record" || return 1
  [ "$OWNED_HOST" = "$expected_host" ] && [ "$OWNED_SPEC" = "$expected_spec" ] || return 1
  kill -0 "$OWNED_PID" 2>/dev/null || return 1
  command_line="$(ps -p "$OWNED_PID" -o command= 2>/dev/null)" || return 1
  case "$command_line" in
    *"$PROXY"*"--owner-token $OWNED_TOKEN"*"$OWNED_HOST"*"$OWNED_SPEC"*) ;;
    *) return 1 ;;
  esac
  if command -v lsof >/dev/null 2>&1; then
    local_port=${expected_spec%%:*}
    listeners="$(lsof -nP -a -p "$OWNED_PID" -iTCP:"$local_port" -sTCP:LISTEN -t 2>/dev/null | sort -u)"
    [ "$listeners" = "$OWNED_PID" ] || return 1
  fi
}

stop_owned() {
  found=0
  for row in "${FLEET[@]}"; do
    set -- $row
    label=$1; ip=$2; lport=$3; spec="$lport:5555"; record="$(record_path "$lport")"
    if owned_proxy_alive "$record" "$ip" "$spec"; then
      printf '  %-10s pid=%s 127.0.0.1:%s -> %s:5555\n' "$label" "$OWNED_PID" "$lport" "$ip"
      found=1
    elif [ -f "$record" ]; then
      echo "refusing invalid ownership record: $record" >&2
      return 2
    fi
  done
  [ "$found" -eq 1 ] || { echo "no live repository-owned proxies"; return 0; }
  printf 'Stop only the repository-owned proxies listed above? [y/N] '
  read -r answer
  [ "$answer" = "y" ] || [ "$answer" = "Y" ] || { echo "cancelled"; return 1; }
  for row in "${FLEET[@]}"; do
    set -- $row
    ip=$2; lport=$3; spec="$lport:5555"; record="$(record_path "$lport")"
    if owned_proxy_alive "$record" "$ip" "$spec"; then
      pid=$OWNED_PID
      kill "$pid"
      stopped=0
      for _ in 1 2 3 4 5 6 7 8 9 10; do
        if ! kill -0 "$pid" 2>/dev/null; then
          stopped=1
          break
        fi
        sleep 0.1
      done
      if [ "$stopped" -ne 1 ]; then
        echo "owned proxy pid=$pid did not stop; record retained at $record" >&2
        return 2
      fi
      rm -f -- "$record"
    fi
  done
}

command -v lsof >/dev/null 2>&1 || {
  echo "lsof is required to validate exact proxy port ownership" >&2
  exit 2
}

case "$ACTION" in
  start) ;;
  --stop-owned) stop_owned; exit $? ;;
  *) echo "usage: $0 [--stop-owned]" >&2; exit 2 ;;
esac

mkdir -p "$STATE_DIR"

for row in "${FLEET[@]}"; do
  set -- $row
  label=$1; ip=$2; lport=$3; spec="$lport:5555"; record="$(record_path "$lport")"
  if owned_proxy_alive "$record" "$ip" "$spec"; then
    printf '  %-10s reusing owned pid=%s\n' "$label" "$OWNED_PID"
    continue
  fi
  listeners=""
  if command -v lsof >/dev/null 2>&1; then
    listeners="$(lsof -nP -iTCP:"$lport" -sTCP:LISTEN -t 2>/dev/null | sort -u)"
  fi
  if [ -n "$listeners" ]; then
    echo "refusing occupied 127.0.0.1:$lport (unowned pid(s): $listeners)" >&2
    exit 2
  fi
  token="telecam-$(date +%s)-$$-$lport"
  log="$STATE_DIR/$lport.log"
  nohup python3 "$PROXY" --owner-token "$token" "$ip" "$spec" >"$log" 2>&1 &
  pid=$!
  tmp_record="$record.tmp.$$"
  printf '%s|%s|%s|%s|%s\n' "$pid" "$token" "$PROXY" "$ip" "$spec" > "$tmp_record"
  mv "$tmp_record" "$record"
  ready=0
  for _ in 1 2 3 4 5 6 7 8 9 10; do
    if ! kill -0 "$pid" 2>/dev/null; then break; fi
    if command -v lsof >/dev/null 2>&1 && \
       [ "$(lsof -nP -a -p "$pid" -iTCP:"$lport" -sTCP:LISTEN -t 2>/dev/null | sort -u)" = "$pid" ]; then
      ready=1
      break
    fi
    sleep 0.1
  done
  if [ "$ready" -ne 1 ]; then
    echo "proxy did not claim 127.0.0.1:$lport; owned record retained at $record; see $log" >&2
    exit 2
  fi
done

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
