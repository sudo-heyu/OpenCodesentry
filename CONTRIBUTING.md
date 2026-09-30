# Contributing

Thanks for taking a look. This is a small, focused project — issues and pull
requests are welcome.

## Before you start

- **Android 12+** (`minSdk 31`), AGP 9 / Kotlin, `targetSdk 37`.
- **The app is a companion, not a replacement.** It only *receives* events from
  a self-hosted OpenCode server; it does not drive OpenCode.
- By participating you agree your contributions are licensed under the
  [MIT License](LICENSE).

## Build & test

```sh
./gradlew testDebugUnitTest     # unit tests (run these before every PR)
./gradlew assembleDebug         # app/build/outputs/apk/debug/app-debug.apk
```

The unit tests cover the pure logic (address parsing, event mapping, vendor
detection) and run on the JVM — no device or emulator needed.

## Code style

- Kotlin, 4-space indent, `kotlin.code.style=official`.
- Keep the code comments explaining **why**, not **what** — the existing code
  follows that convention.
- No new dependencies without a good reason; the project deliberately stays lean.
- Anything that logs must not log credentials. `Logx` is the only logger.

## Signing (maintainers)

Release builds are signed from a local `keystore.properties` that is **never
committed** (see `.gitignore`). Without it, `assembleRelease` produces an
unsigned APK. See the release checklist in the wiki/issue tracker.

## Pull requests

- One logical change per PR.
- Describe the **problem** and the **approach**, and attach a screenshot for any
  UI change.
- Make sure `./gradlew testDebugUnitTest` passes.
