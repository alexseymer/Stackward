# Stackward — System Architecture

> Describes what the code does **today** (after the read-only narrowing of 2026-11-12, PRD D5–D8).
> [PRD.md](../PRD.md) is the spec; [THREAT_MODEL.md](THREAT_MODEL.md) covers what can go wrong.
> Earlier designs (tiered permission engine, on-device Gemma, Proxmox API, risk-gated actions,
> background polling) were removed; see [PHASES.md](PHASES.md) for that history.

## Overview

One Android module (`:app`, package `dev.stackward`). The phone opens an SSH session to a host,
runs `bash ~/.stackward/check.sh`, parses its JSON and shows the result. Nothing else is run on
a host except the app's own onboarding and key-management steps.

```
 phone                                                         host (your own)
┌──────────────────────────────────────────────┐             ┌────────────────────┐
│ Compose UI ── ViewModels ── AppContainer     │             │ stackward-agent    │
│   Dashboard · HostDetail · Onboarding ·      │             │  ~/.ssh/authorized │
│   Settings                                   │   SSH       │  ~/.stackward/     │
│        │                                     │ (key from   │    check.sh        │
│  check/ ── runner, parser, store, health     │  Keystore,  │  (read-only: df,   │
│        │                                     │  TOFU-pinned│   free, systemctl, │
│  connection/ ── SSHJ, jump host, knock ──────┼────────────▶│   journalctl,      │
│        │                                     │  host keys) │   docker ps, ss)   │
│  crypto/ · security/ · permissions/(audit)   │             └────────────────────┘
└──────────────────────────────────────────────┘
```

## Packages

| Package | Responsibility |
|---|---|
| `ui/` | `AppNavigation` (Onboarding → Dashboard → HostDetail / Settings), screens and ViewModels. All text comes from `res/values{,-de}/strings.xml`. |
| `di/AppContainer` | Manual dependency wiring; one instance, held by `StackwardApplication`. |
| `check/` | `CheckScriptRunner` (run, and install/refresh the script), `CheckScriptBundle` (the APK's `assets/check.sh`, version header, install command), `CheckScriptParser` + `CheckModels` (JSON ⇄ model, `coverage`, `advice`), `CheckResultStore` (last result and last failure per host), `HostHealthMapper` (clean / issues / unreachable / stale / never checked), `LegacyBackgroundCleanup` (one-time cancel of jobs from old builds). |
| `connection/` | `SshConnectionManager` (SSHJ; direct or via jump host; retry with backoff), `TofuHostKeyVerifier` + `HostKeyPinStore` (trust on first use, fail closed on change), `AgentSshKeyProvider` (signs with the Keystore/software key), `ConnectionHealthRepository`. |
| `onboarding/` | `OnboardingFlow` (login probe → key install → key login verified → check.sh install → profile saved), `PortKnocker`, `ServerProfileRepository`. |
| `crypto/` | `AgentKeyManager` (Ed25519; Keystore on API 33+, encrypted software key otherwise), `SecurePrefs` (EncryptedSharedPreferences), `SessionSecretVault` (one-time password/PEM, memory only), `OpenSshPublicKeyEncoder`. |
| `security/` | `KeyRotationService`, `PanicRevokeService`, `AuthorizedKeysCommands` (the only host-side commands besides check.sh; they edit the agent's own `authorized_keys`, no sudo), `SecuritySettingsRepository`. |
| `permissions/` | `AuditLogRepository` (last 200 entries: rotations, revokes, elevation acknowledgments). Historical package name. |

## Flows

### Check (the main path)

1. User taps *Check now* / *Check all hosts* (biometric prompt is the intended gate — see issue #85 for what is verified).
2. `CheckScriptRunner.run` executes `bash ~/.stackward/check.sh` over `SshConnectionManager`.
3. Output → `CheckScriptParser` → `CheckResult` (`issues[]` with optional `advice`, `coverage`, `version`).
4. If the script is missing, or its `version` is older than the bundled one, the runner installs the bundled copy
   (base64 over the same SSH session type, temp file + `mv`, mode 700, home directory only) and runs again.
   If installing fails, an older working script's result is still shown; otherwise the error points at `scripts/bootstrap_linux.sh`.
5. Success → `CheckResultStore.save` (clears the stored failure). Failure → `saveFailure`. `HostHealthMapper` turns
   stored data into the row state; a failure newer than the last success wins, results older than 24 h are *stale*.

### Onboarding

Login probe (detects root / passwordless sudo → explicit acknowledgment, audit-logged) → optional port knock and jump host →
key install via the one-time password (`SessionSecretVault`, wiped afterwards) → verify key login → TOFU pin → install `check.sh`
→ save profile. The app never runs `bootstrap_linux.sh`; that script is an optional admin tool (journal/Docker read access).

### Key rotation and panic revoke

Rotation: generate a second key, append it with `AuthorizedKeysCommands.pushKey`, verify login with it, remove the old key's line,
switch the active alias. Panic revoke: audit-log export, `revokeAll` (empty `authorized_keys`) on the selected host, then wipe local
keys, profiles, pins, results, settings and the audit log even if the server step failed. Both need a biometric confirmation.

## Host side

`scripts/check.sh` is the single source; copies must stay identical (CI: `scripts/verify_check_sh_sync.sh`):
`scripts/check.sh`, the heredoc in `scripts/bootstrap_linux.sh`, and `app/src/main/assets/check.sh`. Bump the
`# stackward-check-version:` header on every change. `scripts/tests/check_sh_test.sh` runs it against stubbed commands.
`bootstrap_linux.sh` (admin-run, optional) adds journal and Docker log read access; it installs no sudo rules.

## Invariants and where they are enforced

| Invariant (PRD §1) | Enforced by | Checked by |
|---|---|---|
| Nothing leaves the device except over your SSH | No other network code; `allowBackup=false` | Review (INV-1 needs a device run) |
| No SSH without you present | No scheduler or notification code; `LegacyBackgroundCleanup` | `grep WorkManager` → only the cleanup; INV-2 on device |
| Read-only | Host text is only displayed; no executor besides the fixed commands | `CheckScriptParserTest`, `AuthorizedKeysCommandsTest`, review |
| No elevated privileges | No sudo in app commands; no sudoers rules from the bootstrap | `grep sudo app/src/main` |
| Changed host key fails closed | `TofuHostKeyVerifier` | `TofuHostKeyVerifierTest` |

## Build and CI

Gradle (`:app`), Kotlin + Compose, JDK 17, `compileSdk 37`, `minSdk 28`. CI: unit tests, lint, assemble, shellcheck, check.sh
fixture tests, copy-sync guard, UI-string guard, CodeQL. Release APKs come from `scripts/release_apk.sh` / the Release APK workflow.
