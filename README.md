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

An Android "lookout" for self-hosted infrastructure — plain Linux hosts,
Proxmox, and Docker. The phone queries a lightweight script
(`~/.stackward/check.sh`) installed on each host over SSH, gets back
structured anomalies, and gates any suggested fix by risk: safe runs
immediately, risky needs a fresh biometric confirmation, scary is never
automated at all. An optional on-device Gemma model can summarize what it
found in plain English. No credentials, logs, or biometric data ever leave
the device except over your own SSH connection to your own infrastructure —
never a cloud API.

See [STRATEGY.md](STRATEGY.md) for the thesis, [PRD.md](PRD.md) for the full
product spec, [docs/USER_STORIES.md](docs/USER_STORIES.md) for acceptance
criteria, [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the system
design, and [docs/PHASES.md](docs/PHASES.md) for the pre-pivot build this
was built on top of.

## Why Stackward

- **Local-first, private.** check.sh runs on your own host; the phone reads
  its JSON output over your own SSH connection. Any on-device Gemma inference
  never leaves the phone either — no cloud API, ever.
- **The phone decides risk, not the host.** A suggestion's own claimed risk
  is display-only — `CheckActionCatalog` independently classifies every
  suggestion id against its own allowlist before deciding whether to run it,
  so a compromised or buggy check.sh can't talk its way past the gate.
- **Least-privilege by construction.** A dedicated `stackward-agent` identity
  per host (SSH-key-only, hardware-backed Keystore key) with a handful of
  narrow, single-purpose sudoers helpers — no standing broad `sudo`, no
  `docker` group by default.

## Features

- **Dashboard** — every configured host at a glance, colored by worst issue
  severity found; tap one to see its issues and suggestions.
- **Guided key install** — create `stackward-agent` on the host (`sudo adduser
  stackward-agent`), then enter IP + one-time password; the app installs its
  SSH key (ssh-copy-id style) and wipes the password.
- **Per-host polling** — manual-only, or auto-check every 4 hours via
  WorkManager, with critical-issue-only notifications (best-effort — checks
  still run even without notification permission).
- **Risk-gated suggestions** — safe runs immediately and is logged; risky
  shows the literal command and requires a fresh biometric confirmation;
  scary/unrecognized actions are never executed, just shown as a manual
  workaround.
- **On-device summarization (optional)** — summarize a host's current issues
  in plain English via an imported Gemma model; degrades to the structured
  list with no model imported or if inference fails.
- **Jump-host support** — reach LAN-only infra via `ProxyJump`-style tunneling
  with per-hop host-key pinning (TOFU).
- **Hardening** — audit-log export, key rotation, one-tap panic revoke
  (clears per-host check.sh state and cancels scheduled polling too).

<details>
<summary>Also present: the pre-pivot log-reading/tiered-action system</summary>

Before the Lookout pivot, Stackward had a broader design where an on-device
Gemma model read raw journal/Docker/Proxmox logs directly and proposed
individual shell commands, gated by a three-tier permission engine
(read-only / one-time elevation / boundary change). That code
(`PermissionEngine`, `LogSummarizer`, the Logs screen's journal/Docker tabs)
still exists and still works — reachable from the dashboard's toolbar — but
it's no longer the primary flow. See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)
for how the two fit together.

</details>

## Status

**"Lookout" is implemented: read-only triage via a host-side check script,
model optional, risk-based action gating.** See [STRATEGY.md](STRATEGY.md)
for the thesis and [PRD.md](PRD.md) for the full spec.

| Area | Scope | State |
|------|-------|-------|
| Check script | `~/.stackward/check.sh`: log/disk/service/security detection, JSON output | ✅ Implemented |
| Bootstrap | `scripts/bootstrap_linux.sh` installs `check.sh` + a dedicated sudoers helper for the one RISKY suggestion that needs root | ✅ Implemented |
| Dashboard | Multi-host list (`dev.stackward.ui.dashboard`), per-host detail, per-host manual/auto-4h polling | ✅ Implemented |
| Risk-gated actions | `CheckActionCatalog`/`CheckSuggestionGate`: phone decides risk, never trusts the host's self-reported label; safe auto-runs, risky needs biometric, scary/unknown are manual-only | ✅ Implemented |
| Gemma summary | Host detail screen reuses the existing `LogSummarizer` pipeline; degrades the same way the Logs screen does (no model / inference failure / success) | ✅ Implemented |
| Notifications | `CheckNotifier`: critical-issue-only, best-effort on the POST_NOTIFICATIONS permission | ✅ Implemented |

Verified: `:app:testDebugUnitTest`, `:app:assembleDebug`, `:app:lintDebug` all
green; two review passes (`/simplify` for reuse/efficiency, `/code-review` for
correctness) both ran clean after fixing what they found. **Not yet
dogfooded** against real infrastructure or a physical device — see
[docs/PHASES.md](docs/PHASES.md) for the pre-pivot architecture this was
built on top of (`PermissionEngine`, `AgentKeyManager`, host-key TOFU pinning
— all still in use) and what dogfooding still needs to cover.

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

**Invariants:** check.sh runs on your host, not the phone; the phone reads
its output and decides risk itself — it never trusts what a suggestion
claims about its own risk.

- **Safe:** read-only (tail logs, list failed units) — runs immediately, logged.
- **Risky:** needs root the agent user doesn't have by default (e.g. vacuuming
  the journal) — literal command shown, fresh biometric confirmation required,
  executed via a narrow single-purpose sudoers helper, never a broad grant.
- **Scary / unrecognized:** never executed — shown as a manual workaround
  instead (e.g. disabling SSH password auth).

The dedicated `stackward-agent` identity has no standing `sudo` beyond a
handful of named helper scripts, each independently re-validating its own
input rather than trusting the phone. See [PRD.md § 5](PRD.md#5-core-concepts)
for details.

The pre-pivot Logs screen's tiered permission engine (routine / one-time
elevation / boundary change, gating individual Gemma-proposed shell commands)
still exists alongside this — see the collapsed section above.

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
