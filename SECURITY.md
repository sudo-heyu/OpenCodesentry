# Security Policy

## Reporting a vulnerability

Please **do not** open a public issue for security problems. Use GitHub's
private vulnerability reporting instead:

1. Go to the repository's **Security** tab.
2. Click **Report a vulnerability**.

You will get a response as soon as possible. Please include:

- what you found and where (file / screen / API),
- how to reproduce it,
- the impact you believe it has.

## Scope & threat model

OpenCode Sentry is a **self-hosted** companion. Worth knowing:

- **The transport is Tailscale.** The app talks plain HTTP, but only over your
  own tailnet (WireGuard). Nothing is sent to a third party and there is no
  telemetry.
- **The OpenCode password is a secret.** It is stored encrypted at rest
  (Keystore-backed `EncryptedSharedPreferences`) and is never written to logs.
  Please do not paste real passwords, tokens or tailnet addresses into issues.
- **The accessibility service is "blind".** `canRetrieveWindowContent=false` and
  `packageNames` is limited to the app's own package; it observes nothing.
- **`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` / exact alarms** are used solely to
  keep the alert channel alive on aggressive vendor ROMs.

## Supported versions

Only the latest release is supported.
