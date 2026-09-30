#!/usr/bin/env bash
# Deprecated path — the installer now lives at tools/bridge/install-bridge-macos.sh.
# Kept so old documentation and shell history keep working.
set -euo pipefail
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
exec "$REPO/tools/bridge/install-bridge-macos.sh" "$@"
