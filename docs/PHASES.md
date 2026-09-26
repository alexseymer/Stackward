# Stackward — Build Phases

Two build sequences: the **pre-pivot phases (0–5)** that built the
credential/connection layer and the legacy log/Gemma subsystem, and the
**Lookout phases (A–F)** built on top of them per `STRATEGY.md`'s
Implementation Order. Both are done; current focus is dogfooding, not new
phases — see [Current focus](#current-focus) below.

## Current stage

**All phases, pre-pivot (0–5) and Lookout (A–F), are implemented.**
`:app:testDebugUnitTest`, `:app:assembleDebug`, and `:app:lintDebug` are all
green; the code has been through a `/simplify` pass (reuse/efficiency) and a
`/code-review` pass (correctness — found and fixed a RISKY-action privilege
gap and a panic-revoke leak). **Not yet dogfooded** against real
infrastructure or a physical device.

| Phase | Focus | State |
|-------|-------|-------|
| 0 / 1 | Bootstrap & credential provisioning (SSH + Proxmox, jump-host) | ✅ Done |
| 2 | On-device Gemma integration (legacy log-summarization path) | ✅ Done |
| 3 | Tiered permission engine (legacy path) | ✅ Done, scope narrowed to Monitor-only for v1 |
| 4 | MVP: unified log reading (legacy path) | ✅ Done |
| 5 | Hardening | ✅ Done, extended for check.sh state (see F) |
| A | Check script core (`scripts/check.sh`) | ✅ Done |
| B | Bootstrap integration (`check.sh` + `stackward-check-action` helper) | ✅ Done |
| C | Dashboard + per-host polling | ✅ Done |
| D | Risk-gated suggestion execution (safe/risky/scary) | ✅ Done |
| E | Optional Gemma summary of check.sh results | ✅ Done |
| F | Critical-issue notifications | ✅ Done |

---

## Lookout Phases (A–F)

See `STRATEGY.md § Implementation Order` for the original plan; this
section records what actually shipped for each.

### Phase A — Check Script Core

**Goal:** A single host-side script that detects anomalies and reports them
as structured JSON, replacing the idea of the phone running individual
diagnostic commands itself.

| Task | Status |
|------|--------|
| Disk & memory detection (warn/critical thresholds) | Done |
| Failed systemd unit detection | Done |
| Security checks (SSH password auth, listening port count) | Done |
| Journal error volume + Docker exited-container detection | Done |
| JSON output: `{timestamp, hostname, issues[], suggestions[]}` | Done |

**Exit criteria:** `scripts/check.sh` runs standalone and produces valid
JSON matching the PRD schema. Verified via the `stackward-devhost` MCP
server's `run_check_script_locally` tool (no host needed).

### Phase B — Bootstrap Integration

**Goal:** Get `check.sh` onto a monitored host without a second install step.

| Task | Status |
|------|--------|
| Embed `check.sh` verbatim in `scripts/bootstrap_linux.sh` | Done |
| Keep the two copies in sync | Done (`scripts/verify_check_sh_sync.sh` — catches drift; caught and fixed one real instance) |
| Install `stackward-check-action` sudoers helper for the one RISKY suggestion needing root | Done |

**Exit criteria:** Running `bootstrap_linux.sh` on a fresh host leaves
`~/.stackward/check.sh` installed and runnable by the `stackward-agent` user.

**Re-bootstrap note:** hosts provisioned before this phase need a fresh
bootstrap (or manual install of `check.sh` and `stackward-check-action`) to
use the dashboard.

### Phase C — Dashboard & Per-Host Polling

**Goal:** Multi-host UI replacing the single-host Logs screen as the landing
experience.

| Task | Status |
|------|--------|
| Dashboard: all hosts, colored by worst issue severity | Done |
| Host detail: issues + suggestions for one host | Done |
| Per-host polling setting: manual or auto-check every 4h | Done (`HostPollingRepository`, `CheckWorker` — one WorkManager job per host) |
| "Add host" flow reuses existing onboarding wizard | Done |
| Legacy Logs screen kept reachable, not removed | Done (toolbar icon) |

**Exit criteria:** User opens the app, sees all configured hosts and their
status without picking one first.

### Phase D — Risk-Gated Suggestion Execution

**Goal:** Turn a check.sh suggestion into a gated, executable action —
without trusting the host's own risk claim.

| Task | Status |
|------|--------|
| `CheckActionCatalog`: phone-side action-id → {risk, command, description} table | Done |
| `CheckSuggestionGate`: resolve to AutoApprove/RequireConfirmation/ManualOnly | Done |
| Safe: auto-run + audit log | Done |
| Risky: literal command shown, biometric confirm, then run | Done |
| Scary/unrecognized: never executed, manual workaround shown | Done |
| Privileged risky execution via a dedicated, re-validating sudoers helper | Done (`code-review` caught this missing; fixed) |

**Exit criteria:** Applying a safe suggestion runs immediately; a risky one
requires biometric confirmation and actually succeeds against a real host
(not just an unprivileged command that silently fails).

### Phase E — Optional Gemma Summary

**Goal:** Reuse the existing on-device summarization pipeline for check.sh
results instead of building a second one.

| Task | Status |
|------|--------|
| Host detail "Summarize with Gemma" button | Done, reuses `LogSummarizer` as-is |
| Degrades identically to the legacy Logs screen (no model / failure / success) | Done |
| Ignores model-proposed actions — text summary only | Done, deliberate (see `docs/ARCHITECTURE.md`) |

**Exit criteria:** With no model imported, the summary section explains why
and the structured list is still fully usable; with a model imported, it
produces a plain-English summary.

### Phase F — Notifications

**Goal:** Alert on critical issues from scheduled background checks only.

| Task | Status |
|------|--------|
| `CheckNotifier`: critical-severity-only, best-effort | Done |
| Fires from `CheckWorker` (scheduled), not manual "check now" | Done |
| `POST_NOTIFICATIONS` runtime permission requested when enabling auto-polling | Done |
| Panic revoke cancels scheduled jobs + clears check state | Done (`code-review` caught this missing; fixed) |

**Exit criteria:** A critical issue found during a scheduled background
check produces a notification if permission was granted; checks and the
rest of the app work identically either way.

---

## Pre-Pivot Phases (0–5)

Condensed — these built the credential/connection layer (still used by both
subsystems) and the legacy log/Gemma path (still functional, no longer
primary). Full detail in git history; kept here for what still needs
re-bootstrapping and why.

### Phase 0/1 — Bootstrap & Credential Provisioning

Install this device's SSH public key into a user-chosen account (recommended:
`stackward-agent`); generate an Ed25519 keypair in Android Keystore
(`setUserAuthenticationRequired(true)`); pin the host key (TOFU); support
jump hosts; optionally provision a scoped Proxmox API token. All done.

### Phase 2 — Local Model Integration (legacy)

On-device Gemma via MediaPipe LLM Inference API; auto-selects E2B/E4B by
device RAM; emits structured proposals (`ActionProposalParser`); degrades
to no-AI when no model is imported. All done. Layer 6 of the new
architecture reuses this pipeline as-is for check.sh summaries.

### Phase 3 — Tiered Permission Engine (legacy)

`PermissionEngine` classifies model-proposed actions into
Routine/One-timer/Boundary-change; `stackward-onetimer` sudoers helper
executes One-timer commands after biometric confirmation; Boundary-change
is draft-only. Done, but v1's `CapabilityPack.MONITOR` denies anything above
Routine — see `docs/ARCHITECTURE.md` for why this couldn't just be reused
for check.sh suggestions.

### Phase 4 — MVP: Unified Log Reading (legacy)

`journalctl`/Docker-log-ACL/Proxmox-task-log reading, pre-filtered before
inference, scheduled hourly via WorkManager. Done; reachable via the
dashboard's Logs icon.

### Phase 5 — Hardening

Audit log export, key rotation, panic revoke, periodic Tier-1-rule review
reminder, multi-hop TOFU verification, reconnect/backoff. Done — panic
revoke was extended in Phase F to also cover check.sh's per-host state.

**Re-bootstrap note:** hosts provisioned before Phase 3 or 5 need a fresh
bootstrap (or manual install of the relevant helper scripts) for those
features to work.

---

## Current focus

With every planned phase implemented, work shifts from building to
dogfooding:

- **Real infrastructure.** Everything above has been build-verified and
  code-reviewed, but never run against a live Linux/Proxmox/Docker host —
  validate `check.sh`'s detection accuracy and the risky-action helper
  against real conditions, not just unit tests.
- **Physical device.** On-device Gemma inference needs real hardware
  (emulators aren't reliable for it) — validate the summarization path and
  biometric confirmation flows end-to-end.
- **False-positive tuning.** `check.sh`'s thresholds (disk %, journal error
  volume, listening port count) are initial guesses — see PRD.md's
  false-positive-rate success metric.
- **Acceptance criteria.** [USER_STORIES.md](USER_STORIES.md) has been
  updated for the Lookout flow; close any gaps found during real-hardware use.
