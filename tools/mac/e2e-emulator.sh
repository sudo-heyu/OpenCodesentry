#!/usr/bin/env bash
# End-to-end run of the real APK against the real OpenCode server, on an
# Android emulator. Every step prints evidence, so the output doubles as the
# test record.
#
# Prerequisites:
#   - the bridge is running:  python3 tools/bridge/opencode-bridge.py --bind 127.0.0.1
#   - an emulator or device is up (10.0.2.2 reaches the host loopback)
set -uo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ADB="$HOME/Library/Android/sdk/platform-tools/adb"
PKG="app.opencodesentry"
APK="$REPO/app/build/outputs/apk/debug/app-debug.apk"
HOST_PORT="4096"
# adb reverse maps the guest's own loopback to the host's, so the app talks to
# 127.0.0.1:4096 inside the emulator and lands on the bridge on the Mac.
HOST_ALIAS="127.0.0.1"
PW="$(python3 -c "import json;print(json.load(open('$HOME/.config/opencode/service.json'))['password'])")"
# Emulators have no Tailscale, so the phone-side address stays empty here; on a
# real device the app fills it in by itself on first launch.
PHONE_IP="${PHONE_IP:-}"

section() { printf '\n########## %s ##########\n' "$*"; }
step() { printf '\n$ %s\n' "$*"; "$@"; }

section "0. device"
step "$ADB" wait-for-device
until [ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do
  printf '.'; sleep 3
done
printf '\n'
printf 'Android release : %s\n' "$("$ADB" shell getprop ro.build.version.release | tr -d '\r')"
printf 'SDK level       : %s\n' "$("$ADB" shell getprop ro.build.version.sdk | tr -d '\r')"

section "1. install"
step "$ADB" install -r -g "$APK"

section "1b. connect the guest loopback to the host bridge"
step "$ADB" reverse --remove-all
step "$ADB" reverse "tcp:$HOST_PORT" "tcp:$HOST_PORT"
step "$ADB" reverse --list
printf 'host side listening check:\n'
lsof -nP -iTCP:"$HOST_PORT" -sTCP:LISTEN | tail -2

section "2. clear logcat and seed the connection"
"$ADB" logcat -c 2>/dev/null || true
# Settings live in an encrypted preferences file now, so they can no longer be
# pushed in from outside the app; the debug build accepts them as extras.
step "$ADB" shell am start -n "$PKG/.MainActivity" \
    --es seed_host "$HOST_ALIAS" \
    --ei seed_port "$HOST_PORT" \
    --es seed_user "opencode" \
    --es seed_password "$PW" \
    --es seed_phone_ip "$PHONE_IP"
sleep 3

section "3. launch the app (it self-heals: enabled=true starts the guard)"
step "$ADB" shell am start -n "$PKG/.MainActivity"
sleep 12

section "4. foreground service state"
"$ADB" shell dumpsys activity services "$PKG" | grep -E "ServiceRecord|isForeground|foregroundServiceType|startRequested|app=" | head -20

section "5. ongoing notification (from the service)"
"$ADB" shell dumpsys notification --noredact > /tmp/notif_before.txt
grep -nE "$PKG|OpenCode 通知守护|通道" /tmp/notif_before.txt | head -20

section "6. app log after startup"
"$ADB" logcat -d -s OpenCodeNotify:I | tail -25

section "7. trigger a REAL opencode event over the live stream"
step "$ADB" logcat -c
CREATE=$(curl -s -u "opencode:$PW" -X POST -H 'Content-Type: application/json' \
  -d '{"title":"e2e-alert-check"}' "http://127.0.0.1:$HOST_PORT/api/session")
SID=$(printf '%s' "$CREATE" | python3 -c "import json,sys;print((json.load(sys.stdin).get('data') or {}).get('id',''))")
printf 'created session: %s\n' "$SID"
curl -s -u "opencode:$PW" -X POST -H 'Content-Type: application/json' \
  -d '{"text":"Do not call any tools. Reply with exactly: OK"}' \
  "http://127.0.0.1:$HOST_PORT/api/session/$SID/prompt" > /dev/null
printf 'prompt sent, waiting for the turn to fail/finish...\n'
for i in $(seq 1 60); do
  if ! curl -s -u "opencode:$PW" "http://127.0.0.1:$HOST_PORT/api/session/active" | grep -q "$SID"; then
    printf 'session left the active set after %s polls\n' "$i"; break
  fi
  sleep 2
done
sleep 6

section "8. what the phone received (app log)"
"$ADB" logcat -d -s OpenCodeNotify:I | tail -25

section "9. notifications on the phone"
"$ADB" shell dumpsys notification --noredact > /tmp/notif_after.txt
grep -nE "$PKG|任务完成|任务失败|需要授权|需要你回答|已连接|OpenCode" /tmp/notif_after.txt | head -40

section "10. alert assets exercised"
printf 'active MediaPlayers / audio focus:\n'
"$ADB" shell dumpsys audio | grep -iE "OpenCodeNotify|player piid|AudioFocus stack" | head -10 || true

section "11. cleanup"
curl -s -o /dev/null -w "delete probe session -> %{http_code}\n" -u "opencode:$PW" -X DELETE "http://127.0.0.1:$HOST_PORT/api/session/$SID"

section "done"
