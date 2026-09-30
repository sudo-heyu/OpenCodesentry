#!/usr/bin/env python3
"""Generate the pre-recorded voice prompts used by OpencodeNotify.

Uses the free Microsoft Edge TTS endpoint through the `edge-tts` package.
Output goes straight into app/src/main/res/raw so the files ship as fixed
resources: no network and no TTS engine needed on the phone, and every clip
can be previewed instantly in the options screen.

    python3 tools/tts/generate_voice.py          # regenerate everything
    python3 tools/tts/generate_voice.py --list   # show planned files
    python3 tools/tts/generate_voice.py --only done
"""

from __future__ import annotations

import argparse
import asyncio
import sys
from pathlib import Path

import edge_tts

REPO_ROOT = Path(__file__).resolve().parents[2]
RAW_DIR = REPO_ROOT / "app" / "src" / "main" / "res" / "raw"

# voice id -> (edge-tts voice, label shown in the app)
# Keep the ids in sync with Voice.kt.
VOICES = {
    "xiaoyi": ("zh-CN-XiaoyiNeural", "晓伊 · 女声"),
    "xiaoxiao": ("zh-CN-XiaoxiaoNeural", "晓晓 · 女声"),
    "yunxi": ("zh-CN-YunxiNeural", "云希 · 男声"),
    "yunyang": ("zh-CN-YunyangNeural", "云扬 · 男声（播报）"),
}

# scenario id -> spoken text (keep these short: they play on every alert)
# Keep the ids in sync with AlertKind.voiceKey.
PHRASES = {
    "done": "任务完成",
    "permission": "需要授权",
    "question": "需要你回答",
    "error": "任务失败",
    "interrupted": "任务已中断",
}

# A little quicker than default so an alert is not a monologue.
RATE = "+15%"
VOLUME = "+0%"


def target(scenario: str, voice_id: str) -> Path:
    return RAW_DIR / f"tts_{scenario}_{voice_id}.mp3"


async def render(text: str, voice: str, out: Path) -> None:
    communicate = edge_tts.Communicate(text, voice, rate=RATE, volume=VOLUME)
    await communicate.save(str(out))


async def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--list", action="store_true", help="only print planned files")
    parser.add_argument("--only", choices=sorted(PHRASES), help="render a single scenario")
    parser.add_argument("--voice", choices=sorted(VOICES), help="render a single voice")
    args = parser.parse_args()

    plan = [
        (scenario, voice_id, label)
        for scenario in PHRASES
        if args.only in (None, scenario)
        for voice_id, (_voice, label) in VOICES.items()
        if args.voice in (None, voice_id)
    ]

    if args.list:
        for scenario, voice_id, label in plan:
            print(f"{target(scenario, voice_id).name:34} {label}  «{PHRASES[scenario]}»")
        return 0

    RAW_DIR.mkdir(parents=True, exist_ok=True)

    # Drop clips from an older voice set so stale files cannot linger.
    keep = {target(s, v).name for s, v, _ in plan}
    for stale in RAW_DIR.glob("tts_*.mp3"):
        if stale.name not in keep:
            stale.unlink()
            print(f"removed stale {stale.name}")

    failures = 0
    for scenario, voice_id, label in plan:
        text = PHRASES[scenario]
        out = target(scenario, voice_id)
        try:
            await render(text, VOICES[voice_id][0], out)
        except Exception as exc:  # noqa: BLE001 - report and keep going
            failures += 1
            print(f"FAIL {out.name}: {exc}", file=sys.stderr)
            continue
        print(f"OK   {out.name:34} {out.stat().st_size:>6} B  {label}  «{text}»")
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
