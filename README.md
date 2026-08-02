# Stackward

> **Stackward** = steward of your stack.

[![CI](https://github.com/alexseymer/Stackward/actions/workflows/ci.yml/badge.svg)](https://github.com/alexseymer/Stackward/actions/workflows/ci.yml)
[![CodeQL](https://github.com/alexseymer/Stackward/actions/workflows/codeql.yml/badge.svg)](https://github.com/alexseymer/Stackward/actions/workflows/codeql.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Android-3DDC84?logo=android&logoColor=white)](#build-locally)
[![API](https://img.shields.io/badge/API-28%2B-brightgreen.svg)](#build-locally)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.1-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Jetpack Compose](https://img.shields.io/badge/UI-Jetpack%20Compose-4285F4?logo=jetpackcompose&logoColor=white)](https://developer.android.com/jetpack/compose)
[![On-device LLM](https://img.shields.io/badge/LLM-Gemma%20on--device-4285F4?logo=google&logoColor=white)](docs/MODEL_SETUP.md)
[![Release](https://img.shields.io/badge/release-0.5.4--dogfood-orange.svg)](docs/PHASES.md)
[![Status: dogfood](https://img.shields.io/badge/status-dogfood-yellow.svg)](#status)

On-device (Gemma E2B/E4B) Android agent for monitoring and managing
self-hosted infrastructure — plain Linux hosts, Proxmox, and Docker — over
SSH, with a tiered, human-confirmed permission model. No credentials,
logs, or biometric data ever leave the device except over your own
SSH/API connections to your own infrastructure.

See [PRD.md](PRD.md) for the full product spec, [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)
for the system design, and [docs/PHASES.md](docs/PHASES.md) for the build
order.

## Why Stackward

- **Local-first, private.** All inference runs on the phone via the MediaPipe
  LLM Inference API. Prompts, logs, and command output never touch a third-party
  cloud — only your own SSH/API endpoints.
- **The model proposes, the human disposes.** Gemma emits *structured* action
  proposals (never raw shell); a permission engine gates every one before it
  reaches a server.
- **Least-privilege by construction.** A dedicated `gemma-agent` identity per host
  (SSH-key-only, hardware-backed Keystore key) and a scoped Proxmox API token —
  no standing broad `sudo`, no `docker` group by default.

## Features

- **Zero-touch bootstrap** — enter an IP + one-time admin credential; the app
  provisions the `gemma-agent` user, restricted key, sudoers stub, and (optional)
  Proxmox token, then discards the admin credential.
- **Unified log reading** — systemd journal, Docker container logs, and Proxmox
  task logs through one read-only, zero-elevation pipeline.
- **Scheduled digests** — hourly anomaly digest across all three sources via
  WorkManager, with reconnect-and-backoff for unattended runs.
- **On-device summarization** — ask "what's wrong with container X" and get a
  correlated summary; degrades gracefully to raw logs when no model is imported.
- **Tiered, biometric-gated actions** — one-time service restarts / VM power
  actions require an explicit confirmation of the literal command plus a fresh
  biometric check; boundary changes are human-only.
- **Jump-host support** — reach LAN-only infra via `ProxyJump`-style tunneling
  with per-hop host-key pinning (TOFU).
- **Hardening** — audit-log export, key rotation, one-tap panic revoke, and a
  periodic Tier 1 rule review reminder.

## Status

**Early scaffold — dogfood build (`0.5.4-dogfood`), not yet production-ready.**

All planned phases are feature-complete at scaffold level; current focus is
dogfooding and stabilization on real hardware. See [docs/PHASES.md](docs/PHASES.md)
for the roadmap and per-task detail.

| Phase | Scope | State |
|-------|-------|-------|
| 0 / 1 | Bootstrap, SSH user/key & Proxmox token provisioning, jump-host support | ✅ Implemented |
| 2 | On-device Gemma summarization (MediaPipe LLM Inference API) | ✅ Implemented |
| 3 | Tiered permission engine (sudoers + Proxmox role backend) | ✅ Implemented |
| 4 | MVP: unified log reading (journal + Docker + Proxmox), read-only | ✅ Implemented |
| 5 | Hardening: key rotation, panic revoke, audit export, Tier 1 review | ✅ Implemented |

See [docs/MODEL_SETUP.md](docs/MODEL_SETUP.md) for importing an on-device model.

## Build locally

Requires **JDK 17** and the **Android SDK** (`compileSdk 37`; `minSdk 28`,
`targetSdk 35`). Built with Gradle 9.5 and Jetpack Compose.

```bash
export ANDROID_HOME=~/Android/Sdk
./gradlew :app:assembleDebug        # debug APK
./gradlew :app:testDebugUnitTest    # unit tests
./gradlew :app:lintDebug            # Android lint
```

Open the project in Android Studio for emulator/device testing. On-device LLM
inference requires a **physical device** (emulators are not reliable for it) — see
[docs/MODEL_SETUP.md](docs/MODEL_SETUP.md).

## Security model, in short

- **Tier 1 (routine):** read-only or pre-vetted actions, no prompt.
- **Tier 2 (one-timer):** named elevation, explicit confirmation +
  biometric, single-use.
- **Tier 3 (boundary change):** editing what the agent is allowed to do —
  never automated, human-only.

The model never gets raw shell access. It emits structured proposals that
pass through a permission engine before any command reaches a server.
See [PRD.md § 5](PRD.md#5-core-concepts) for details.

## Repo layout

```
PRD.md                  Full product requirements
docs/ARCHITECTURE.md    System design
docs/PHASES.md          Build roadmap
docs/MODEL_SETUP.md     On-device model import
scripts/                Server-side bootstrap scripts (reviewed by user before running)
app/                    Android app source (scaffold)
```

## Contributing / running locally

Not yet ready for external contribution — this is a personal
infrastructure tool. Scaffold will fill in as phases land.

## License

Released under the [MIT License](LICENSE).
