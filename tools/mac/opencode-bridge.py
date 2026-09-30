#!/usr/bin/env python3
"""Deprecated path — the bridge now lives at tools/bridge/opencode-bridge.py.

Kept as a thin shim so a launchd agent (or shell history) that still points
here keeps working. New installs should use tools/bridge/.
"""

from __future__ import annotations

import runpy
import sys
from pathlib import Path

TARGET = Path(__file__).resolve().parent.parent / "bridge" / "opencode-bridge.py"

if not TARGET.is_file():
    print(f"error: {TARGET} not found", file=sys.stderr)
    raise SystemExit(1)

sys.argv[0] = str(TARGET)
runpy.run_path(str(TARGET), run_name="__main__")
