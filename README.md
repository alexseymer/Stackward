# Stackward

> **Stackward** = steward of your stack.

[![CI](https://github.com/alexseymer/Stackward/actions/workflows/ci.yml/badge.svg)](https://github.com/alexseymer/Stackward/actions/workflows/ci.yml)
[![CodeQL](https://github.com/alexseymer/Stackward/actions/workflows/codeql.yml/badge.svg)](https://github.com/alexseymer/Stackward/actions/workflows/codeql.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Android-3DDC84?logo=android&logoColor=white)](#build-locally)
[![API](https://img.shields.io/badge/API-28%2B-brightgreen.svg)](#build-locally)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Jetpack Compose](https://img.shields.io/badge/UI-Jetpack%20Compose-4285F4?logo=jetpackcompose&logoColor=white)](https://developer.android.com/jetpack/compose)
[![Release](https://img.shields.io/badge/release-0.5.12--dogfood-orange.svg)](https://github.com/alexseymer/Stackward/releases/latest)
[![Status: dogfood](https://img.shields.io/badge/status-dogfood-yellow.svg)](#status)

> [!WARNING]
> **This project was built with AI assistance and has not been fully tested.**
> Most of the code was written by an AI coding assistant. It has automated
> tests and has passed AI-assisted code reviews, but it has not yet been
> exercised on a real phone against real infrastructure, and parts of it
> (notably SSH key handling and the biometric flow) may not work as intended.
> The app handles SSH credentials and opens shells on your servers, so
> review the code yourself and use it at your own risk — try it on a test host
> first, never on production. See [Status](#status) and the
> [tracking issue](https://github.com/alexseymer/Stackward/issues/96) for the
> known gaps.

Stackward is an Android app that tells a solo operator, from their phone and within
10 seconds of a fingerprint, whether any of their Linux hosts has a problem right now —
and **changes nothing on those hosts**. It connects over SSH with a key that never leaves
the phone, runs a small read-only script (`~/.stackward/check.sh`) that the app installs
into the agent user's home, and shows what it found: failed services, full disks, memory
pressure, journal and Docker trouble, risky SSH settings — plus an explicit list of what
it could *not* read, so a blind check never looks like an all-clear. No credentials, logs
or check results leave the device except over your own SSH connection; there is no cloud,
no telemetry, no on-device model, no background activity and no remediation.

Scope was narrowed on 2026-11-12 ([PRD](PRD.md) decisions D5–D8): earlier builds had risk-gated
actions, an on-device Gemma summary, Proxmox support and raw log screens. They are removed.

See [STRATEGY.md](STRATEGY.md) for the thesis, [PRD.md](PRD.md) for the full
product spec (its [§11](PRD.md#11-spec-ahead-of-code-open-work-as-of-2026-11-12)
lists where the spec is deliberately ahead of the code),
[docs/ACCEPTANCE.md](docs/ACCEPTANCE.md) for the v1 acceptance criteria and release gate,
[docs/USER_STORIES.md](docs/USER_STORIES.md) for per-story detail,
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the system design,
[docs/PHASES.md](docs/PHASES.md) for the build history, and
[docs/INSTALL.md](docs/INSTALL.md) for installing a build on a phone.

## Why Stackward

Built the Linux way ([docs/PRINCIPLES.md](docs/PRINCIPLES.md)): one job done well, small programs that work together
(`check.sh` is a standalone filter — run `bash scripts/check.sh | jq` on any host with no app), text as the interface,
and development in the open with frequent releases.

- **Local-first, private.** check.sh runs on your own host; the phone reads its JSON
  over your own SSH connection. Nothing else is sent anywhere.
- **Read-only by construction.** The app has no code path that runs anything the host
  tells it to. `advice` text is shown for you to read; there is no Apply button, no catalog
  and no `sudo` helper. See [docs/THREAT_MODEL.md](docs/THREAT_MODEL.md).
- **Least privilege.** A dedicated `stackward-agent` user per host (SSH-key-only; Android
  Keystore key, biometric-bound on devices that support it), no `sudo`, no `docker` group by
  default. Key rotation and revoke edit the agent's own `authorized_keys`.
- **You're always present.** No SSH session starts without you in the app
  ([PRD invariant 2](PRD.md#1-summary)); there are no scheduled checks or notifications.
- **Honest about blind spots.** If the journal, Docker or systemd could not be read, the
  host row says so; unreachable and stale hosts are shown as such, never green.

## Features

- **Dashboard** — every host at a glance (clean / issues / unreachable / stale / never checked),
  with the age of its last check, a per-host refresh and **Check all hosts**.
- **Host detail** — issues with copyable `advice`, the blind spots of the last check, the last
  error with Retry.
- **Guided key install** — create `stackward-agent` on the host (`sudo adduser
  stackward-agent`), then enter IP + one-time password; the app installs its SSH key
  (ssh-copy-id style), installs `check.sh` into `~/.stackward/` and wipes the password.
  The script is refreshed automatically when the app ships a newer version.
- **Jump-host support** — reach LAN-only hosts via `ProxyJump`-style tunneling with per-hop
  host-key pinning (TOFU), plus optional port knocking.
- **Hardening** — audit-log export, key rotation, one-tap panic revoke (wipes local state and
  empties the agent's `authorized_keys`).

## Status

Read-only health check for Linux hosts; see [PRD.md](PRD.md) for the spec and
[docs/ACCEPTANCE.md](docs/ACCEPTANCE.md) for the v1 release gate.

| Area | Scope | State |
|------|-------|-------|
| Check script | `~/.stackward/check.sh`: disk (real filesystems only), memory, failed units, SSH password auth, listening ports, journal errors, exited Docker containers; `advice` per issue; `coverage` for journal/docker/systemd; versioned | ✅ Implemented, fixture-tested (`scripts/tests/check_sh_test.sh`) |
| Install | App installs and refreshes `check.sh` (home dir only, no root); `scripts/bootstrap_linux.sh` (admin-run) can also grant journal/Docker read access | ✅ Implemented, ⚠️ not yet run on a real host |
| Dashboard | Health states, staleness, persisted failures, Check all, host detail | ✅ Implemented |
| Hardening | Key rotation, panic revoke, audit export, TOFU pinning, jump host | ✅ Implemented, ⚠️ not device-verified |
| Biometric-bound SSH signing | Keystore key requires biometric per use; the signing path may not drive a per-use prompt | ❌ Needs on-device verification ([#85](https://github.com/alexseymer/Stackward/issues/85)) |

CI runs unit tests, lint, shellcheck, the check.sh fixture suite, the check.sh copy-sync guard and
CodeQL. **Nothing has been verified on a physical device or against a real host yet.** The first
thing to check on a real phone is whether SSH key signing works. Open work: the
[tracking issue](https://github.com/alexseymer/Stackward/issues/96).

Latest build: see [Releases](https://github.com/alexseymer/Stackward/releases/latest).
Install it with `adb install -r app-dogfood.apk` — Chrome sideloading often
fails on Pixels in 2026; see [docs/INSTALL.md](docs/INSTALL.md).

## Build locally

Requires **JDK 17** and the **Android SDK** (`compileSdk 37`; `minSdk 28`,
`targetSdk 35`). Built with the Gradle wrapper (9.7), Kotlin 2.4 and Jetpack
Compose. CI (`.github/workflows/ci.yml`) runs the same three commands below
plus dogfood assembly and APK-signature checks.

```bash
export ANDROID_HOME=~/Android/Sdk
./gradlew :app:assembleDebug        # debug APK
./gradlew :app:testDebugUnitTest    # unit tests
./gradlew :app:lintDebug            # Android lint
```

Open the project in Android Studio for emulator/device testing. Biometric key use needs a
**physical device**.

### Cutting a phone build

Run `./scripts/release_apk.sh` (needs the Android SDK and an authenticated
GitHub CLI), or — anywhere else — trigger the **Release APK** workflow from
the Actions tab (or `gh api repos/alexseymer/Stackward/actions/workflows/release.yml/dispatches -f ref=main`).
Either path bumps the version past the newest release tag on the `builds`
branch, builds and signature-checks the dogfood APK, and publishes a GitHub
Release with the APK attached. Details in [AGENTS.md](AGENTS.md).

### Dev onboarding prefills

To skip retyping SSH credentials while dogfooding, add keys from
[`local.properties.example`](local.properties.example) to your gitignored
`local.properties`, set `stackward.dev.prefill=true`, then rebuild debug.
Release builds never include these values.

## Security model, in short

**Invariants:** nothing leaves the device except over your own SSH (no cloud, no telemetry);
no SSH session starts without you present (no background checks, no notifications); the app
changes nothing on hosts except its own `~/.stackward/` files and the agent's `authorized_keys`;
the agent user needs no `sudo`. Text from a host (messages, advice, unit names) is shown, never
executed. Details and residual risks: [docs/THREAT_MODEL.md](docs/THREAT_MODEL.md) and
[PRD §5.2](PRD.md#52-read-only-guarantee).

## Repo layout

```
STRATEGY.md             The chosen direction ("Lookout") and why
PRD.md                  Product requirements (§11: where the spec is ahead of the code)
AGENTS.md               Guide for coding agents: source of truth, build, release
docs/ACCEPTANCE.md      v1 acceptance scenarios, pass criteria, sign-off log
docs/USER_STORIES.md    Per-story criteria & dogfood checklist
docs/ARCHITECTURE.md    System design
docs/PHASES.md          Build history and current focus
docs/INSTALL.md         Installing a build on a Pixel (ADB), lost-phone revoke
docs/PRINCIPLES.md      The Linux principle: design and process rules
docs/THREAT_MODEL.md    Assets, trust boundaries, threats and proof
scripts/                check.sh, admin bootstrap scripts (reviewed before running), release tooling
mcp-servers/            Dev-only MCP server for iterating on check.sh
.github/workflows/      CI, CodeQL, and the manual Release APK workflow
app/                    Android app (single :app module, package dev.stackward)
```

## Contributing / running locally

Not yet ready for external contribution — this is a personal
infrastructure tool for a single operator.

## License

Released under the [MIT License](LICENSE).
