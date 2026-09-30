# Changelog

All notable changes to this project are documented here. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.0] - 2026-09-30

First public release.

### Added

- Foreground service holding an SSE connection to a self-hosted OpenCode
  server, exposed over Tailscale by a cross-platform Python bridge.
- Alerts for task completion, permission prompts, questions and failures
  (`session.idle`, `permission.asked`, `form.created`, `session.error`, …).
- Four-channel alerting: notification, vibration, ringtone and offline Chinese
  TTS voice (4 voices pre-generated in the APK).
- Three-layer keep-alive: accessibility keeper, persistent `JobScheduler` and an
  exact-alarm watchdog, plus a single-poll fallback.
- Multi-vendor shortcuts for auto-start / background settings (Xiaomi, Huawei,
  OPPO, vivo, Samsung, Meizu).
- Cross-platform bridge `tools/bridge/opencode-bridge.py` (macOS / Linux /
  Windows) with installers for launchd, systemd and Windows Task Scheduler.
- Credentials encrypted at rest (Keystore-backed `EncryptedSharedPreferences`)
  and excluded from cloud backup / device transfer.

[1.0.0]: https://github.com/sudo-heyu/OpenCodesentry/releases/tag/v1.0
