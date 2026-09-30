#!/usr/bin/env bash
# Probe v2: capture the FULL payload of the terminal events for a turn,
# including the failure reason, so the notification mapping is grounded in
# observed data rather than guesses.
set -u

PW=$(python3 -c "import json;print(json.load(open('$HOME/.config/opencode/service.json'))['password'])")
BASE="http://127.0.0.1:49374"
AUTH="opencode:$PW"
RAW=/tmp/opencode_probe2_raw.txt

echo "### creating session (with directory)"
CREATE=$(curl -s -u "$AUTH" -X POST -H 'Content-Type: application/json' \
  -d '{"title":"event-probe-2","directory":"$(pwd)"}' \
  "$BASE/api/session")
echo "$CREATE" | head -c 400; echo
SID=$(printf '%s' "$CREATE" | python3 -c "
import json,sys
d=json.load(sys.stdin)
print((d.get('data') or d).get('id',''))
")
[ -z "$SID" ] && { echo "no session id"; exit 1; }
echo "session=$SID"

: > "$RAW"
curl -s -N -m 180 -u "$AUTH" -H "Accept: text/event-stream" "$BASE/api/event" > "$RAW" &
SSE=$!
sleep 2

echo "### prompting"
curl -s -u "$AUTH" -X POST -H 'Content-Type: application/json' \
  -d '{"text":"Do not call any tools. Reply with exactly: OK"}' \
  "$BASE/api/session/$SID/prompt" | head -c 200; echo

echo "### waiting for the session to leave the active set"
for i in $(seq 1 90); do
  if ! curl -s -u "$AUTH" "$BASE/api/session/active" | grep -q "$SID"; then
    echo "left active set after ${i} polls"; break
  fi
  sleep 2
done
sleep 4
kill $SSE 2>/dev/null; wait $SSE 2>/dev/null

echo "### GET /api/session/\$SID (raw)"
curl -s -u "$AUTH" "$BASE/api/session/$SID" | head -c 400; echo

echo "### terminal events for the probe session"
python3 - "$RAW" "$SID" <<'PY'
import json, sys
raw, sid = sys.argv[1], sys.argv[2]
KEEP = ("session.execution", "session.idle", "session.error", "session.step.failed",
        "permission.", "form.")
for line in open(raw, errors="replace"):
    line = line.strip()
    if not line.startswith("data:"):
        continue
    try:
        ev = json.loads(line[5:].strip())
    except Exception:
        continue
    data = ev.get("data") or {}
    if data.get("sessionID") != sid:
        continue
    t = ev.get("type", "")
    if not t.startswith(KEEP):
        continue
    print(f"  {t}\n      {json.dumps(data)[:700]}")
PY

echo "### cleanup"
curl -s -o /dev/null -w "delete=%{http_code}\n" -u "$AUTH" -X DELETE "$BASE/api/session/$SID"
