#!/usr/bin/env bash
# macOS: install (or remove) a per-user launchd agent that keeps the OpenCode
# bridge running — started at login, restarted if it ever exits.
#
#   ./tools/bridge/install-bridge-macos.sh
#   ./tools/bridge/install-bridge-macos.sh --uninstall
#
# Without this, the bridge dies on reboot / logout and the phone starts
# reporting "Failed to connect to <host>".
set -euo pipefail

LABEL="dev.opencode.bridge"
PORT="${PORT:-4096}"
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BRIDGE="$REPO/tools/bridge/opencode-bridge.py"
PYTHON="$(command -v python3 || true)"
PLIST="$HOME/Library/LaunchAgents/$LABEL.plist"
LOG="/tmp/opencode-bridge.log"

if [ "${1:-}" = "--uninstall" ]; then
  launchctl bootout "gui/$(id -u)/$LABEL" 2>/dev/null || true
  rm -f "$PLIST"
  echo "removed $LABEL"
  exit 0
fi

if [ "$(uname -s)" != "Darwin" ]; then
  echo "error: this installer is for macOS; use install-bridge-linux.sh or install-bridge-windows.ps1" >&2
  exit 1
fi
if [ -z "$PYTHON" ]; then
  echo "error: python3 not found on PATH" >&2
  exit 1
fi
if [ ! -f "$BRIDGE" ]; then
  echo "error: $BRIDGE not found" >&2
  exit 1
fi

mkdir -p "$HOME/Library/LaunchAgents"

cat > "$PLIST" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key>
    <string>$LABEL</string>
    <key>ProgramArguments</key>
    <array>
        <string>$PYTHON</string>
        <string>$BRIDGE</string>
        <string>--port</string>
        <string>$PORT</string>
    </array>
    <key>RunAtLoad</key>
    <true/>
    <key>KeepAlive</key>
    <true/>
    <key>ThrottleInterval</key>
    <integer>10</integer>
    <key>StandardOutPath</key>
    <string>$LOG</string>
    <key>StandardErrorPath</key>
    <string>$LOG</string>
</dict>
</plist>
EOF

# Replace any previous instance, including a hand-started nohup one.
launchctl bootout "gui/$(id -u)/$LABEL" 2>/dev/null || true
pkill -f "opencode-bridge.py" 2>/dev/null || true
sleep 1

launchctl bootstrap "gui/$(id -u)" "$PLIST"
sleep 6

echo "installed: $PLIST"
echo "log:       $LOG"
echo
launchctl print "gui/$(id -u)/$LABEL" 2>/dev/null | grep -E "state =|pid =" || true
echo
echo "--- listening ---"
lsof -nP -iTCP:"$PORT" -sTCP:LISTEN || echo "not listening yet; check $LOG"
echo
echo "--- log tail ---"
tail -6 "$LOG" 2>/dev/null || true
