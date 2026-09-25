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
[![Release](https://img.shields.io/badge/release-0.5.11--dogfood-orange.svg)](docs/PHASES.md)
[![Status: dogfood](https://img.shields.io/badge/status-dogfood-yellow.svg)](#status)

On-device (Gemma E2B/E4B) Android agent for monitoring and managing
self-hosted infrastructure — plain Linux hosts, Proxmox, and Docker — over
SSH, with a tiered, human-confirmed permission model. No credentials,
logs, or biometric data ever leave the device except over your own
SSH/API connections to your own infrastructure.

See [PRD.md](PRD.md) for the full product spec, [docs/USER_STORIES.md](docs/USER_STORIES.md)
for acceptance criteria, [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)
for the system design, and [docs/PHASES.md](docs/PHASES.md) for the build
order.

## Why Stackward

- **Local-first, private.** All inference runs on the phone via the MediaPipe
  LLM Inference API. Prompts, logs, and command output never touch a third-party
  cloud — only your own SSH/API endpoints.
- **The model proposes, the human disposes.** Gemma emits *structured* action
  proposals (never raw shell); a permission engine gates every one before it
  reaches a server.
- **Least-privilege by construction.** A dedicated `stackward-agent` identity per host
  (SSH-key-only, hardware-backed Keystore key) and a scoped Proxmox API token —
  no standing broad `sudo`, no `docker` group by default.

## Features

- **Guided key install** — create `stackward-agent` on the host (`sudo adduser
  stackward-agent`), then enter IP + one-time password; the app installs its
  SSH key (ssh-copy-id style) and wipes the password. Optional admin-run
  bootstrap scripts add sudoers helpers and Proxmox tokens.
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

**Pivoting to "Lookout": read-only triage via a host-side check script,
model optional, risk-based action gating.** See [STRATEGY.md](STRATEGY.md)
for the current north star and [PRD.md](PRD.md) for the full spec — both
supersede the phase table below and the docs it links to.

The table below reflects an **earlier, broader architecture** (on-device
Gemma emitting individual Tier 1/2/3 shell proposals over a persistent
SSH/Proxmox-API connection) that was built out to scaffold level before the
Lookout pivot. Much of that code still exists and still works
(`PermissionEngine`, `ProxmoxCommands`, `AgentKeyManager`), but v1 scope is
now Monitor-only — see `CapabilityPack.kt` and
[docs/PHASES.md](docs/PHASES.md) (flagged there as historical).

| Phase | Scope | State |
|-------|-------|-------|
| 0 / 1 | Bootstrap, SSH user/key & Proxmox token provisioning, jump-host support | ✅ Implemented |
| 2 | On-device Gemma summarization (MediaPipe LLM Inference API) | ✅ Implemented |
| 3 | Tiered permission engine (sudoers + Proxmox role backend) | ⚠️ Implemented, scope narrowed to Monitor-only for v1 |
| 4 | MVP: unified log reading (journal + Docker + Proxmox), read-only | ✅ Implemented |
| 5 | Hardening: key rotation, panic revoke, audit export, Tier 1 review | ✅ Implemented |
| — | **New:** `~/.stackward/check.sh` structured anomaly detector (Lookout) | ✅ Script done |
| — | **New:** Dashboard (multi-host), per-host polling, risk-gated suggestions (safe/risky/scary) | ⚠️ Implemented, unverified — see note below |

**Unverified note:** the dashboard/risk-gating code (`dev.stackward.check`,
`dev.stackward.ui.dashboard`) was written and manually reviewed line-by-line
against existing call signatures, but has **not** been compiled — the
environment it was written in had no network access to `dl.google.com`, so
`./gradlew` could not resolve the Android Gradle Plugin. Run
`./gradlew :app:testDebugUnitTest :app:assembleDebug` before trusting it;
the pure-logic pieces (`CheckScriptParser`, `CheckActionCatalog`,
`CheckSuggestionGate`) have unit tests, the Compose UI does not.

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

### Dev onboarding prefills

To skip retyping SSH credentials while dogfooding, add keys from
[`local.properties.example`](local.properties.example) to your gitignored
`local.properties`, set `stackward.dev.prefill=true`, then rebuild debug.
Release builds never include these values.

## Security model, in short

**Invariants:** on-device inference; the model proposes, you (or a rule you
approved) dispose.

**User policy:** how privileged the SSH/API identity is, and which work is
in scope — safe defaults (`stackward-agent`, read/maintenance), elevated
accounts and broader scopes only with explicit acknowledgment.

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
docs/USER_STORIES.md    Acceptance criteria & dogfood checklist
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
