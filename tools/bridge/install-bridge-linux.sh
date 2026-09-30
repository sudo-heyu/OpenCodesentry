#!/usr/bin/env bash
# Linux: install (or remove) a systemd *user* service that keeps the OpenCode
# bridge running — started at login, restarted if it ever exits.
#
#   ./tools/bridge/install-bridge-linux.sh
#   ./tools/bridge/install-bridge-linux.sh --uninstall
#
# To keep it alive even while you are logged out:
#   sudo loginctl enable-linger "$USER"
set -euo pipefail

LABEL="opencode-bridge"
PORT="${PORT:-4096}"
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BRIDGE="$REPO/tools/bridge/opencode-bridge.py"
PYTHON="$(command -v python3 || true)"
UNIT_DIR="${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user"
UNIT="$UNIT_DIR/$LABEL.service"

if [ "${1:-}" = "--uninstall" ]; then
  systemctl --user disable --now "$LABEL" 2>/dev/null || true
  rm -f "$UNIT"
  systemctl --user daemon-reload 2>/dev/null || true
  echo "removed $LABEL"
  exit 0
fi

if [ "$(uname -s)" != "Linux" ]; then
  echo "error: this installer is for Linux; use install-bridge-macos.sh or install-bridge-windows.ps1" >&2
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
if ! command -v systemctl >/dev/null 2>&1; then
  echo "error: systemctl not found; run the bridge by hand instead:" >&2
  echo "       $PYTHON $BRIDGE --port $PORT" >&2
  exit 1
fi

mkdir -p "$UNIT_DIR"

cat > "$UNIT" <<EOF
[Unit]
Description=OpenCode bridge (expose the loopback API to the tailnet)
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
ExecStart=$PYTHON $BRIDGE --port $PORT
Restart=always
RestartSec=5

[Install]
WantedBy=default.target
EOF

systemctl --user daemon-reload
systemctl --user enable --now "$LABEL"

echo "installed: $UNIT"
echo "log:       journalctl --user -u $LABEL -f"
echo
systemctl --user --no-pager --lines=0 status "$LABEL" 2>/dev/null | head -5 || true
echo
echo "--- listening ---"
(ss -ltnp 2>/dev/null || netstat -ltnp 2>/dev/null) | grep ":$PORT" || echo "not listening yet; check the journal"
echo
echo "tip: to keep it running while logged out ->  sudo loginctl enable-linger $USER"
