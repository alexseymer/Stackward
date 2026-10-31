# Stackward

> **Stackward** = steward of your stack.

[![CI](https://github.com/alexseymer/Stackward/actions/workflows/ci.yml/badge.svg)](https://github.com/alexseymer/Stackward/actions/workflows/ci.yml)
[![CodeQL](https://github.com/alexseymer/Stackward/actions/workflows/codeql.yml/badge.svg)](https://github.com/alexseymer/Stackward/actions/workflows/codeql.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Android-3DDC84?logo=android&logoColor=white)](#build-locally)
[![API](https://img.shields.io/badge/API-28%2B-brightgreen.svg)](#build-locally)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Jetpack Compose](https://img.shields.io/badge/UI-Jetpack%20Compose-4285F4?logo=jetpackcompose&logoColor=white)](https://developer.android.com/jetpack/compose)
[![On-device LLM](https://img.shields.io/badge/LLM-Gemma%20on--device-4285F4?logo=google&logoColor=white)](docs/MODEL_SETUP.md)
[![Release](https://img.shields.io/badge/release-0.5.12--dogfood-orange.svg)](https://github.com/alexseymer/Stackward/releases/latest)
[![Status: dogfood](https://img.shields.io/badge/status-dogfood-yellow.svg)](#status)

> [!WARNING]
> **This project was built with AI assistance and has not been fully tested.**
> Most of the code was written by an AI coding assistant. It has automated
> tests and has passed AI-assisted code reviews, but it has not yet been
> exercised on a real phone against real infrastructure, and parts of it
> (notably SSH key handling and the biometric flow) may not work as intended.
> The app handles SSH credentials and can run commands on your servers, so
> review the code yourself and use it at your own risk — try it on a test host
> first, never on production. See [Status](#status) and the
> [tracking issue](https://github.com/alexseymer/Stackward/issues/96) for the
> known gaps.

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
product spec (its [§11](PRD.md#11-spec-ahead-of-code-open-work-as-of-2026-10-23)
lists where the spec is deliberately ahead of the code),
[docs/USER_STORIES.md](docs/USER_STORIES.md) for acceptance criteria,
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the system design,
[docs/PHASES.md](docs/PHASES.md) for the build history, and
[docs/INSTALL.md](docs/INSTALL.md) for installing a build on a phone.

## Why Stackward

- **Local-first, private.** check.sh runs on your own host; the phone reads
  its JSON output over your own SSH connection. Any on-device Gemma inference
  never leaves the phone either — no cloud API, ever.
- **The phone decides risk, not the host.** A suggestion's own claimed risk
  is display-only — `CheckActionCatalog` independently classifies every
  suggestion id against its own allowlist before deciding whether to run it,
  so a compromised or buggy check.sh can't talk its way past the gate.
- **Least-privilege by construction.** A dedicated `stackward-agent` identity
  per host (SSH-key-only, Android Keystore key — biometric-bound on devices
  that support it) with a handful of narrow, single-purpose sudoers helpers —
  no standing broad `sudo`, no `docker` group by default.
- **You're always present.** The product rule ([PRD invariant
  2](PRD.md#1-summary)) is that no SSH session starts without you in the app
  confirming with biometric — no unattended access to your hosts.

## Features

- **Dashboard** — every configured host at a glance, colored by worst issue
  severity found; tap one to see its issues and suggestions.
- **Guided key install** — create `stackward-agent` on the host (`sudo adduser
  stackward-agent`), then enter IP + one-time password; the app installs its
  SSH key (ssh-copy-id style) and wipes the password.
- **Per-host polling and notifications** *(shipped, being removed)* —
  manual-only, or auto-check every 4 hours via WorkManager with
  critical-issue-only notifications. The PRD (decision D1) now rules out
  background checks, since they need SSH with nobody there to confirm; this
  is tracked as [gap G1](PRD.md#11-spec-ahead-of-code-open-work-as-of-2026-10-23).
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
  (clears per-host check.sh state and any scheduled polling too).

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
| Check script | `~/.stackward/check.sh`: disk/memory/failed-service/SSH-config/listening-port/journal-error/Docker-exited detection, JSON output | ✅ Implemented |
| Bootstrap | `scripts/bootstrap_linux.sh` (admin-run) installs `check.sh` + a dedicated sudoers helper for the one RISKY suggestion that needs root. **Per PRD D2, in-app onboarding should install `check.sh` itself — today it only installs the SSH key** | ⚠️ Admin script ✅, in-app install ❌ ([G3](PRD.md#11-spec-ahead-of-code-open-work-as-of-2026-10-23)) |
| Dashboard | Multi-host list (`dev.stackward.ui.dashboard`), per-host detail, last-checked age, on-demand "Check now" | ✅ Implemented |
| Polling | Per-host manual / auto-4h (`CheckWorker`) | ⚠️ Shipped, **to be removed** (PRD D1 / G1) |
| Risk-gated actions | `CheckActionCatalog`/`CheckSuggestionGate`: phone decides risk, never trusts the host's self-reported label; safe auto-runs, risky needs biometric, scary/unknown are manual-only | ✅ Implemented |
| Gemma summary | Host detail screen reuses the existing `LogSummarizer` pipeline; degrades the same way the Logs screen does (no model / inference failure / success) | ✅ Implemented |
| Notifications | `CheckNotifier`: critical-issue-only, best-effort on the POST_NOTIFICATIONS permission | ⚠️ Shipped, **to be removed** (PRD D1 / G1) |
| Biometric-bound SSH signing | Keystore key requires biometric per use; the signing path doesn't yet drive a per-use prompt | ❌ Needs on-device verification ([G2](PRD.md#11-spec-ahead-of-code-open-work-as-of-2026-10-23)) |

Verified: `:app:testDebugUnitTest`, `:app:assembleDebug`, `:app:lintDebug` all
green in CI; two review passes (`/simplify` for reuse/efficiency,
`/code-review` for correctness) both ran clean after fixing what they found.
**Not yet dogfooded** against real infrastructure or a physical device — see
[docs/PHASES.md](docs/PHASES.md) for the build history (the pre-pivot
`PermissionEngine`, `AgentKeyManager` and host-key TOFU pinning are all still
in use) and what dogfooding still needs to cover. The first thing to check on
a real phone is whether SSH key signing works (gap G2).

Latest build: see [Releases](https://github.com/alexseymer/Stackward/releases/latest).
Install it with `adb install -r app-dogfood.apk` — Chrome sideloading often
fails on Pixels in 2026; see [docs/INSTALL.md](docs/INSTALL.md).

See [docs/MODEL_SETUP.md](docs/MODEL_SETUP.md) for importing an on-device model.

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

Open the project in Android Studio for emulator/device testing. On-device LLM
inference requires a **physical device** (emulators are not reliable for it) — see
[docs/MODEL_SETUP.md](docs/MODEL_SETUP.md).

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

**Invariants:** check.sh runs on your host, not the phone; the phone reads
its output and decides risk itself — it never trusts what a suggestion
claims about its own risk; nothing leaves the device except over your own SSH
(no cloud, no telemetry); and no SSH session should start without you present
(the shipped auto-polling still violates this — gap G1).

- **Safe:** read-only (tail logs, list failed units) — runs immediately, logged.
- **Risky:** needs root the agent user doesn't have by default (e.g. vacuuming
  the journal) — literal command shown, fresh biometric confirmation required,
  executed via a narrow single-purpose sudoers helper, never a broad grant.
- **Scary / unrecognized:** never executed — shown as a manual workaround
  instead (e.g. disabling SSH password auth, because changing it over the
  connection you're using can lock you out).

The shipped action catalog has four entries: `tail_journal` and
`list_failed_services` (safe), `cleanup_old_logs` (risky), and
`disable_ssh_password_auth` (scary). See [PRD §5.2](PRD.md#52-risk-based-action-gating).

The dedicated `stackward-agent` identity has no standing `sudo` beyond a
handful of named helper scripts, each independently re-validating its own
input rather than trusting the phone. See [PRD.md § 5](PRD.md#5-core-concepts)
for details.

The pre-pivot Logs screen's tiered permission engine (routine / one-time
elevation / boundary change, gating individual Gemma-proposed shell commands)
still exists alongside this — see the collapsed section above.

## Repo layout

```
STRATEGY.md             The chosen direction ("Lookout") and why
PRD.md                  Product requirements (§11: where the spec is ahead of the code)
AGENTS.md               Guide for coding agents: source of truth, build, release
docs/USER_STORIES.md    Acceptance criteria & dogfood checklist
docs/ARCHITECTURE.md    System design
docs/PHASES.md          Build history and current focus
docs/INSTALL.md         Installing a build on a Pixel (ADB), lost-phone revoke
docs/MODEL_SETUP.md     On-device model import
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
