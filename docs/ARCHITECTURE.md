# Stackward — System Architecture

## Overview

> **Spec vs. code.** [PRD.md](../PRD.md) was revised on 2026-10-23 and is
> deliberately ahead of the code in a few places (see its
> [§11](../PRD.md#11-spec-ahead-of-code-open-work-as-of-2026-10-23)). This
> document describes what the code does **today** and marks those places
> with *(→ G#)*.

Two subsystems share one credential/connection layer:

1. **Lookout (primary)** — the phone queries `~/.stackward/check.sh` on each
   host over SSH for structured anomalies, classifies risk itself, and gates
   any suggested fix before running it. This is the dashboard you land on.
2. **Legacy log/Gemma subsystem** — an earlier design where the phone read
   raw journal/Docker/Proxmox logs directly and let an on-device Gemma model
   propose individual shell commands, gated by a three-tier permission
   engine. Still present and working — reachable from the dashboard's
   toolbar — but no longer the primary flow. See
   [Legacy: Log/Gemma Subsystem](#legacy-loggemma-subsystem) below.

```
┌───────────────────────────────────────────────────────────────────────┐
│                           Android Device                              │
│                                                                        │
│  ┌────────────────┐   ┌──────────────────┐   ┌──────────────────┐   │
│  │ check.sh JSON  │──▶│ CheckActionCatalog│──▶│ CheckSuggestionGate│  │
│  │ (parsed)       │   │ (phone decides    │   │ (safe/risky/scary) │  │
│  │                │   │  risk, not host)  │   └─────────┬─────────┘  │
│  └───────▲────────┘   └───────────────────┘             │            │
│          │                                    ┌──────────┴─────────┐ │
│          │                          biometric │ SSH execution      │ │
│          │                          (risky)   │ + audit log        │ │
│          │                                    └──────────┬─────────┘ │
│  ┌───────┴────────┐                                      │           │
│  │ Connection     │◀─────────────────────────────────────┘           │
│  │ Layer (SSH,    │                                                  │
│  │ TOFU pinning)  │   ┌──────────────┐                               │
│  └───────┬────────┘   │ Android      │  Keystore-backed SSH key,     │
│          │            │ Keystore     │  biometric-bound per use      │
│          │            └──────────────┘  (→ G2: verify on device)     │
└──────────┼─────────────────────────────────────────────────────────────┘
           │
           ▼
┌─────────────────────────────────────────┐
│  User's network                          │
│  ┌──────────────┐                        │
│  │ Monitored    │  runs ~/.stackward/    │
│  │ host         │  check.sh when asked    │
│  │              │  (scheduled today → G1) │
│  └──────────────┘                        │
└─────────────────────────────────────────┘
```

## Layers

### 1. Check Script (host-side)

`~/.stackward/check.sh` — a dependency-free, read-only bash script that runs
unprivileged as the SSH user. **Today** it reaches a host only through the
admin-run `scripts/bootstrap_linux.sh`, which embeds it verbatim (kept in sync
by `scripts/verify_check_sh_sync.sh`); in-app onboarding installs just the SSH
key. **Per PRD D2 the app should install and refresh it itself** *(→ G3)*.
It detects disk/memory, failed systemd units, SSH password auth, listening-port
count, journal errors and exited Docker containers (thresholds overridable via
env vars; exact table in [PRD §5.1](../PRD.md#51-the-check-script-stackwardchecksh))
and prints JSON:

```json
{
  "timestamp": "...", "hostname": "...",
  "issues": [{ "type": "...", "severity": "...", "message": "..." }],
  "suggestions": [{ "id": "...", "risk": "...", "action": "...", "reason": "..." }]
}
```

### 2. Connection Layer (shared by both subsystems)

- `SshConnectionManager` (SSHJ) for programmatic SSH — direct and jump-host
  via channel forwarding.
- Host key pinning (TOFU) per hop; a changed key fails closed.
- Reconnect-with-backoff.
- Optional port knocking before connecting (`PortKnocker`).

### 3. Risk Classification (phone-side, the actual security boundary)

- `CheckScriptParser` parses check.sh's JSON into `CheckResult`.
- `CheckActionCatalog` — a fixed, on-device table mapping a suggestion's
  `action` id to `{risk, literal command, description}`. A suggestion's own
  `risk` field is **display-only and never trusted** — a compromised or
  buggy check.sh cannot get an unreviewed action past this gate by claiming
  "safe". An action id the catalog doesn't recognize is always `UNKNOWN`,
  never executed, regardless of what the host reports.
- `CheckSuggestionGate` resolves a suggestion to `AutoApprove` / `RequireConfirmation` /
  `ManualOnly` using only the catalog's classification.

### 4. Execution & Audit

- **Safe** — runs immediately via `SshConnectionManager.execute`, recorded
  by `recordAuditedExecution` (`permissions/AuditedExecution.kt`) into the
  same `AuditLogRepository` the legacy subsystem uses, via a small shared
  helper rather than the legacy `PermissionExecutor`'s tier-gated path.
- **Risky** — literal command shown, `BiometricGate` confirmation required,
  then the same execution/audit path. The one current risky action
  (`cleanup_old_logs`) runs via `sudo /usr/local/sbin/stackward-check-action
  cleanup_old_logs` — a narrow, single-purpose sudoers helper installed by
  bootstrap that independently re-validates the action id against its own
  fixed table before running anything (the phone decides it's safe to send,
  the helper decides it's safe to run — same defense-in-depth as the legacy
  `stackward-onetimer` helper, deliberately not the same mechanism).
- **Scary / unrecognized** — never executed; the catalog's description is
  shown as a manual workaround instead. `disable_ssh_password_auth` is scary
  on purpose: changing it over the SSH connection the phone is using can lock
  the operator out (PRD D3).

The full v1 catalog is four actions — `tail_journal`, `list_failed_services`
(safe), `cleanup_old_logs` (risky), `disable_ssh_password_auth` (scary).

**Why this doesn't reuse `PermissionEngine`:** `PermissionEngine.evaluate`'s
`capabilityDenial` denies any proposal above `PermissionTier.ROUTINE`
outright under the v1 `CapabilityPack.MONITOR` gate (see `CapabilityPack.kt`).
Routing check.sh suggestions through it would silently break the
risky-confirmation flow before it ever ran. The two mechanisms are kept
deliberately separate — see `STRATEGY.md`/`PRD.md`.

### 5. Dashboard & Scheduling (scheduling → G1)

- **Dashboard** (`dev.stackward.ui.dashboard.DashboardScreen`) — every
  configured host, colored by the worst severity in its *last* check, with
  that check's age.
- **Host detail** — issues, suggestions with an Apply button per the gate
  above, and an optional AI summary (see layer 6).
- **Per-host polling** *(shipped, slated for removal — PRD D1 / G1)* —
  manual, or `CheckWorker` (a WorkManager periodic job, one per host) every 4
  hours. `HostPollingRepository` stores the per-host setting.
- **Notifications** *(shipped, slated for removal — PRD D1 / G1)* —
  `CheckNotifier` fires a critical-issue-only notification from
  `CheckWorker`'s scheduled runs (not from a manual "check now"). Best-effort:
  missing the `POST_NOTIFICATIONS` permission just means no alert shows.

**Why these are going away:** a background run opens an SSH session with the
user absent, which the product no longer allows (PRD invariant 2), and a
Keystore key that demands biometric on every use can't sign unattended
anyway. The legacy hourly `LogDigestWorker` has the same problem. Once removed,
checks happen only from the dashboard/host detail after biometric confirmation.

### 6. Optional On-Device Summary

Host detail's "Summarize with Gemma" reuses `LogSummarizer` /
`GemmaInferenceEngine` / `ModelRepository` unchanged — the same optional,
on-device-only pipeline the legacy Logs screen uses, degrading the same way
(no model imported → explains why; inference failure → shows the error;
structured issues/suggestions stay visible regardless). It deliberately
ignores `SummarizationResult.proposals` — this is a text summary only, not a
second way to trigger actions; check.sh suggestions already have their own
gated path (layer 3–4).

### 7. Credential Store (shared)

- Ed25519 keypair in Android Keystore, created with
  `setUserAuthenticationRequired(true)` and per-use authentication
  (validity `-1`), invalidated by biometric re-enrollment. This needs
  Android 13+ and an enrolled strong biometric; otherwise `AgentKeyManager`
  falls back to a **software key** with no biometric binding (see `log.md`).
  **Open issue (G2):** `BiometricGate` authenticates without a `CryptoObject`
  bound to the signature, so whether SSH signing succeeds with the
  per-use key must be verified on a device.
- Proxmox API token, same biometric gate (legacy subsystem only).
- No credentials persisted in plaintext outside Keystore.
- **Panic revoke** clears both subsystems' local state: keys, profiles, pin
  store, audit log, *and* every host's `CheckResultStore` entry plus its
  scheduled `CheckWorker` job — a gap fixed after `/code-review` found the
  wipe path predated check.sh and left scheduled polling running
  indefinitely post-wipe. (When G1 removes polling, this cleanup shrinks with it.)

## Bootstrap Flow

```
Info screen: recommended default — create restricted user
  (Debian: sudo adduser stackward-agent)
         │
         ▼
Connect as chosen SSH user with one-time password or private key
  (default: stackward-agent; elevated accounts allowed with ack;
  optional jump host and port-knock sequence)
         │
         ▼
Login probe: whoami / groups / passwordless sudo?
  → if elevated: show risk warning, require explicit acknowledgment
         │
         ▼
Confirm authorized_keys install (ssh-copy-id style, home of that user)
         │
         ▼
App appends device public key to ~/.ssh/authorized_keys
         │
         ▼
App verifies connection with Keystore agent key
         │
         ▼
Password wiped from memory (never written to disk)
         │
         ▼
(PRD D2, not built yet → G3) App installs/refreshes ~/.stackward/check.sh
```

`scripts/bootstrap_linux.sh` is an **out-of-band admin script** — run by a
real admin on the host, not by the app, and not required for key-only
onboarding. (The app also bundles an older 144-line copy as
`app/src/main/assets/scripts/bootstrap_linux.sh`, loaded only by the unused
`BootstrapRunner`; it lacks `check.sh` and the risky-action helper and is
slated for deletion — G4. Don't use it.) The real script installs, beyond the key:

- `~/.stackward/check.sh` (the Lookout detector, embedded verbatim)
- `systemd-journal` group membership + Docker log ACL (read-only)
- A sudoers.d stub with narrow, single-purpose NOPASSWD helpers only:
  `stackward-onetimer` (legacy Tier 2), `stackward-check-action` (the one
  RISKY check.sh suggestion), `stackward-push-key`/`stackward-revoke-key`/
  `stackward-panic-revoke`/`stackward-sudoers-snapshot` (credential
  lifecycle). No wildcard grants, no broad `sudo`.

Proxmox `pveum` / privileged host helpers remain separate out-of-band admin
scripts (`scripts/bootstrap_proxmox.sh`).

## Legacy: Log/Gemma Subsystem

Pre-dates the Lookout pivot. Still functional, reachable via the dashboard's
"Logs" toolbar icon, but superseded as the primary flow by layers 1–6 above.

An on-device Gemma model reads raw journal/Docker/Proxmox logs and emits
**structured proposals** (JSON, never raw shell), gated by `PermissionEngine`:

| Tier | Engine behaviour |
|------|-------------------|
| **Routine** | Allow if the action matches a pre-vetted rule or is read-only. Log and execute. |
| **One-timer** | Block until the user confirms the exact command string. Biometric re-prompt. Base64-encode the command, run it via the `stackward-onetimer` sudoers helper, which re-validates against its own hardcoded allowlist (`systemctl status|restart *` only). |
| **Boundary change** | Rejected from the automated path entirely. App may draft a suggested diff; a human applies it manually. |

Under v1's `CapabilityPack.MONITOR` (the only pack currently exposed), any
proposal above Routine is denied outright before per-command classification
even runs — see `PermissionEngine.capabilityDenial`. This is what makes it
unsafe to route check.sh suggestions through the same engine (layer 3–4
above exists because of this).

Two backends: SSH/sudoers for Linux host commands, Proxmox API (scoped
token) for VM/LXC status and power actions. Three uniform read-only log
sources — systemd journal (`journalctl` via the `systemd-journal` group),
Docker container logs (file ACL, not the `docker` group), Proxmox task
logs — feed the same summarization pipeline layer 6 reuses.

## Security Invariants

1. The phone decides an action's risk — never the host, and never a model's
   self-reported classification.
2. Biometric data never leaves the device; it gates local key operations only.
3. Scary/boundary-change actions are unreachable from any automated path in
   either subsystem — manual workaround only.
4. Every sudoers helper independently re-validates its own input against a
   fixed table; the phone deciding something is safe to send is never
   sufficient on its own.
5. Docker group membership is opt-in with explicit warning (root-equivalent);
   the default is a read-only file ACL.
6. A host key change on any hop fails closed and alerts, rather than
   silently reconnecting.
7. Elevated SSH identities (root / passwordless sudo) require explicit
   onboarding acknowledgment — never silent.
8. Panic revoke clears state for *both* subsystems, including scheduled
   background jobs — nothing keeps running after an emergency wipe.
9. **No unattended host access** (PRD invariant 2): every SSH session starts
   from the user in the app, confirmed by biometric. *Not yet true in the
   code* — auto-polling and the legacy hourly digest violate it (→ G1).
