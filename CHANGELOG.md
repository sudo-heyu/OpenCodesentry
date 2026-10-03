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

## [Unreleased]

### Added

- Bridge `--pair` mode: create an official OpenCode pairing code and render it as
  a scannable QR (ANSI terminal + `opencode-pair.png`), so a phone on the same
  tailnet can open the OpenCode web client already signed in.
- Vendored, stdlib-only QR generator `tools/bridge/qrgen.py`
  (Project Nayuki, MIT) used by `--pair`.
- Android 「主页」: the official OpenCode web console in a WebView, entered by
  scanning the bridge pairing QR. The redeemed session token doubles as the
  Basic password, so one scan configures both the console and the guard.
- QR scanner `ScanActivity` (CameraX + bundled ML Kit, no Play Services) with a
  扫码配对 button on the config page; scan-credential expiry is surfaced there.
- UI redesign: fixed "ink" palette (dynamic colour disabled), no cards — flat
  sections separated by hairlines, compact 44dp rows, 10dp button corners.
  Light and dark modes both re-verified screen by screen on the emulator.
- Text fields restyled: borderless filled boxes with 12dp corners; focus is
  shown by the floating label turning ink instead of an outline.

### Fixed

- Permission alerts fired even when every permission was granted: OpenCode
  still emits `permission.asked` for requests a client auto-approves and
  immediately follows it with `permission.replied` (the official web client's
  autoApprove mode, `opencode run --auto`). The guard now holds the alert for a
  2 s grace period and drops it when the reply wins, so only a request that is
  genuinely waiting for the user rings. Verified against a live server and a
  controlled mock stream on the emulator.
- Console page wasted space top and bottom: the WebView reported the system
  bars as `env(safe-area-inset-*)`, so the official web client padded itself
  again (55px top / 24px bottom) even though the view already sits between the
  app bar and the bottom bar. The WebView now consumes window insets, the safe
  area reads 0 and both the session list and the chat view fill the page.
- 「音色」dropdown label overlapped its value: a `MaterialAutoCompleteTextView`
  inside a plain filled `TextInputLayout` misses Material's exposed-dropdown
  theme overlay, so the floating label never shrank and the field was 48dp
  instead of 56dp. The field now uses a dedicated
  `Widget.Opencode.TextInput.Dropdown` style (official Material pattern).
- Android Studio 2025.3 refuses to sync a project whose AGP is newer than it
  supports. AGP pinned back to **9.2.1** (from 9.4.1) so the project opens in
  that IDE; the Gradle wrapper stays on 9.6.0 and CI is unaffected. Verified
  with `assembleDebug` + `testDebugUnitTest` and `buildEnvironment`
  (`com.android.tools.build:gradle:9.2.1`).
