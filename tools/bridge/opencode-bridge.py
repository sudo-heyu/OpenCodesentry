#!/usr/bin/env python3
"""Expose the OpenCode loopback HTTP API to the Tailscale network.

The OpenCode background service only ever listens on 127.0.0.1 and picks a
random port each time it restarts. This bridge listens on a *stable* port on
the Tailscale interface and forwards to whatever port the service is using
right now, so the phone can keep a fixed URL.

Runs on macOS, Linux and Windows with the standard library only.

    python3 opencode-bridge.py                 # bind to the tailnet IP
    python3 opencode-bridge.py --port 4096
    python3 opencode-bridge.py --bind 0.0.0.0  # also reachable on the LAN

Stop it with Ctrl+C. The API stays protected by OpenCode's HTTP Basic auth.
"""

from __future__ import annotations

import argparse
import asyncio
import json
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

IS_WINDOWS = sys.platform.startswith("win")
IS_MACOS = sys.platform == "darwin"

RESOLVE_INTERVAL = 15.0
IPV4 = re.compile(r"\b(\d{1,3}(?:\.\d{1,3}){3})\b")


# ----------------------------------------------------------------------
# Locating the OpenCode CLI
# ----------------------------------------------------------------------

def _cli_roots() -> list[Path]:
    """Directories that may contain a versioned `cli/<version>/` folder."""
    home = Path.home()
    roots: list[Path] = []
    if IS_MACOS:
        roots.append(home / "Library/Application Support/ai.opencode.desktop/cli")
    elif IS_WINDOWS:
        for var in ("LOCALAPPDATA", "APPDATA"):
            base = os.environ.get(var)
            if base:
                roots.append(Path(base) / "ai.opencode.desktop/cli")
    else:
        roots += [
            home / ".local/share/ai.opencode.desktop/cli",
            home / ".config/ai.opencode.desktop/cli",
        ]
    return roots


def find_opencode_cli() -> Path | None:
    names = ["opencode-cli.exe", "opencode-cli"] if IS_WINDOWS else ["opencode-cli"]
    found: list[Path] = []
    for root in _cli_roots():
        if not root.is_dir():
            continue
        for name in names:
            found += [p for p in root.glob(f"*/{name}") if p.is_file()]
    if found:
        return sorted(found, key=lambda p: p.parent.name)[-1]

    for exe in ("opencode-cli", "opencode"):
        which = shutil.which(exe)
        if which:
            return Path(which)
    return None


# ----------------------------------------------------------------------
# Locating this machine's tailnet address
# ----------------------------------------------------------------------

def is_tailnet(ip: str) -> bool:
    """Tailscale hands out addresses from the CGNAT range 100.64.0.0/10."""
    parts = ip.split(".")
    if len(parts) != 4:
        return False
    try:
        first, second = int(parts[0]), int(parts[1])
    except ValueError:
        return False
    return first == 100 and 64 <= second <= 127


def _interface_commands() -> list[list[str]]:
    if IS_WINDOWS:
        return [["ipconfig"]]
    if IS_MACOS:
        return [["/sbin/ifconfig"], ["ifconfig"]]
    return [["ip", "-4", "addr"], ["ifconfig"]]


def _runnable(command: str) -> bool:
    return bool(shutil.which(command)) or Path(command).exists()


def interface_tailnet_ip() -> str | None:
    """
    Reads the tunnel address straight off the interfaces.

    This is the reliable path: the Tailscale GUI's CLI only works inside a
    logged-in GUI session, and under a service manager it may print an error
    to stdout *while still exiting 0*, so its output cannot be trusted blindly.
    """
    for command in _interface_commands():
        if not _runnable(command[0]):
            continue
        try:
            out = subprocess.run(command, capture_output=True, text=True, timeout=10)
        except Exception:
            continue
        if out.returncode != 0:
            continue
        for candidate in IPV4.findall(out.stdout):
            if is_tailnet(candidate):
                return candidate
    return None


def _tailscale_commands() -> list[list[str]]:
    commands: list[list[str]] = []
    if IS_MACOS:
        commands.append(["/Applications/Tailscale.app/Contents/MacOS/Tailscale"])
    if IS_WINDOWS:
        for var in ("ProgramFiles", "ProgramFiles(x86)"):
            base = os.environ.get(var)
            if base:
                commands.append([str(Path(base) / "Tailscale" / "tailscale.exe")])
    commands += [["tailscale"], ["/usr/bin/tailscale"], ["/usr/local/bin/tailscale"]]
    return commands


def cli_tailnet_ip() -> str | None:
    """Secondary path, used when the interface scan finds nothing."""
    for command in _tailscale_commands():
        if not _runnable(command[0]):
            continue
        try:
            out = subprocess.run(command + ["ip", "-4"], capture_output=True, text=True, timeout=10)
        except Exception:
            continue
        for line in out.stdout.splitlines():
            line = line.strip()
            if is_tailnet(line):
                return line
        if out.stdout.strip():
            print(
                f"[bridge] ignoring unusable output from {Path(command[0]).name}: "
                f"{out.stdout.strip()[:120]}",
                flush=True,
            )
    return None


def tailscale_ip() -> str | None:
    return interface_tailnet_ip() or cli_tailnet_ip()


def service_json_hint() -> str:
    """Best guess at where OpenCode keeps the password on this machine."""
    candidates: list[Path] = []
    if IS_WINDOWS:
        for var in ("APPDATA", "LOCALAPPDATA"):
            base = os.environ.get(var)
            if base:
                candidates.append(Path(base) / "opencode/service.json")
    candidates.append(Path.home() / ".config/opencode/service.json")
    for candidate in candidates:
        if candidate.is_file():
            return str(candidate)
    return str(candidates[0])


# ----------------------------------------------------------------------
# Forwarding
# ----------------------------------------------------------------------

def resolve_service(cli: Path) -> tuple[str, int] | None:
    """Ask the CLI which loopback port the background service is on."""
    try:
        out = subprocess.run(
            [str(cli), "api", "get", "/api/info"],
            capture_output=True,
            text=True,
            timeout=25,
        )
    except Exception:
        return None
    if out.returncode != 0:
        return None
    try:
        info = json.loads(out.stdout)
    except Exception:
        return None
    for url in info.get("urls", []):
        if "127.0.0.1:" in url:
            return "127.0.0.1", int(url.rsplit(":", 1)[1])
    return None


async def refresh_loop(cli: Path, state: dict, interval: float) -> None:
    last: tuple[str, int] | None = None
    while True:
        target = await asyncio.to_thread(resolve_service, cli)
        if target and target != last:
            print(f"[bridge] forwarding to {target[0]}:{target[1]}", flush=True)
            last = target
        elif not target:
            print("[bridge] warning: could not locate the OpenCode service", flush=True)
        state["target"] = target
        await asyncio.sleep(interval)


async def pipe(reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
    try:
        while True:
            chunk = await reader.read(65536)
            if not chunk:
                break
            writer.write(chunk)
            await writer.drain()
    except Exception:
        pass
    finally:
        try:
            writer.close()
        except Exception:
            pass


async def handle(
    reader: asyncio.StreamReader,
    writer: asyncio.StreamWriter,
    state: dict,
) -> None:
    target = state.get("target")
    if not target:
        writer.close()
        return
    try:
        remote_reader, remote_writer = await asyncio.open_connection(*target)
    except Exception:
        writer.close()
        return
    await asyncio.gather(
        pipe(reader, remote_writer),
        pipe(remote_reader, writer),
    )


async def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=4096, help="port to listen on (default 4096)")
    parser.add_argument(
        "--bind",
        default="auto",
        help="interface to bind: 'auto' (Tailscale IP), an address, or 0.0.0.0",
    )
    parser.add_argument("--cli", default="", help="path to the opencode-cli binary")
    parser.add_argument(
        "--bind-retries",
        type=int,
        default=24,
        help="how many times to wait for Tailscale at startup (default 24)",
    )
    parser.add_argument(
        "--bind-retry-delay",
        type=float,
        default=5.0,
        help="seconds between Tailscale probes at startup (default 5)",
    )
    args = parser.parse_args()

    cli = Path(args.cli) if args.cli else find_opencode_cli()
    if cli is None or not cli.exists():
        print("error: could not find opencode-cli; pass --cli", file=sys.stderr)
        return 1

    bind = args.bind
    if bind == "auto":
        detected = None
        for attempt in range(max(1, args.bind_retries)):
            detected = await asyncio.to_thread(tailscale_ip)
            if detected:
                break
            if attempt == 0:
                print("[bridge] waiting for Tailscale to come up…", flush=True)
            await asyncio.sleep(args.bind_retry_delay)
        if detected:
            bind = detected
            print(f"[bridge] binding to Tailscale address {bind}")
        else:
            bind = "0.0.0.0"
            print("[bridge] Tailscale not detected; binding to 0.0.0.0 (LAN + tailnet)")

    state: dict = {"target": None}
    server = await asyncio.start_server(
        lambda r, w: handle(r, w, state),
        host=bind,
        port=args.port,
    )
    refresh = asyncio.create_task(refresh_loop(cli, state, RESOLVE_INTERVAL))

    print(f"[bridge] listening on {bind}:{args.port}")
    print(f"[bridge] set the app server URL to http://{bind}:{args.port}")
    print(f"[bridge] credentials: user 'opencode', password from {service_json_hint()}")

    async with server:
        try:
            await server.serve_forever()
        except asyncio.CancelledError:
            pass
        finally:
            refresh.cancel()
    return 0


if __name__ == "__main__":
    # Windows' default Proactor loop supports start_server(); this is a no-op
    # elsewhere. Kept explicit so the behaviour is obvious.
    if IS_WINDOWS:
        asyncio.set_event_loop_policy(asyncio.WindowsProactorEventLoopPolicy())
    try:
        raise SystemExit(asyncio.run(main()))
    except KeyboardInterrupt:
        print("\n[bridge] stopped")
