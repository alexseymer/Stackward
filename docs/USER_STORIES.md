# Stackward — User Stories & Acceptance Criteria

Detailed acceptance criteria for dogfood testing. High-level outcomes live in
[PRD.md §6](../PRD.md#6-key-user-stories-v1-acceptance).

**Status key:** ✅ implemented · ⚠️ partial / scaffold · ❌ not yet · 🔮 v1.1 stretch

> **2026-10-23:** [PRD](../PRD.md) was revised (decisions D1–D4) and is ahead
> of the code in places — see [PRD §11](../PRD.md#11-spec-ahead-of-code-open-work-as-of-2026-10-23).
> Status marks here describe what the **code does today**; stories whose
> feature the PRD now removes are labelled *to be removed*, and new stories
> for the not-yet-built parts (G3, G4) are marked ❌.

Epics G–J below are the current primary flow (Lookout: dashboard, check.sh,
risk-gated actions). Epics A–F describe the pre-pivot log/Gemma subsystem —
still functional and reachable from the dashboard's toolbar, but no longer
the app's front door. Onboarding (Epic A) is shared by both.

---

## Epic G — Dashboard & multi-host

### G1 — Dashboard glance

**Story:** As a user, I open the app and see all my configured hosts at
once, colored by their worst current issue severity, without picking one
host first.

| AC | Status |
|----|--------|
| Dashboard lists every configured `ServerProfile` | ✅ |
| Each row colored by worst issue severity from its last check (green = clean, red = critical) | ✅ |
| Each row shows how long ago it was last checked | ✅ |
| Tap a host to see its issues + suggestions | ✅ |
| "+" adds another host via the existing onboarding wizard | ✅ |

**Phase:** [Phase C](PHASES.md#phase-c--dashboard--per-host-polling)

---

### G2 — Host detail

**Story:** As a user, tapping a host shows me exactly what's wrong and what
I can do about it.

| AC | Status |
|----|--------|
| Issues list: type, severity, message | ✅ |
| Suggestions list: reason + Apply button per suggestion | ✅ |
| "Check now" re-runs check.sh on demand | ✅ |
| Last-checked timestamp shown | ✅ |

**Phase:** [Phase C](PHASES.md#phase-c--dashboard--per-host-polling)

---

### G3 — Onboarding installs check.sh (PRD D2)

**Story:** As a user, after connecting a host through the app's wizard I can
run a check immediately — I don't have to run an admin script as root first.

| AC | Status |
|----|--------|
| Onboarding installs `~/.stackward/check.sh` as the SSH user (no root needed) | ❌ (today only the SSH key is installed; `check.sh` comes from the admin-run `bootstrap_linux.sh`) |
| The app refreshes the installed copy when its bundled version is newer | ❌ |
| `verify_check_sh_sync.sh` also covers the app's bundled copy | ❌ |
| First check on a freshly onboarded host succeeds without the admin script | ❌ |

**PRD reference:** [§5.1 Delivery](../PRD.md#51-the-check-script-stackwardchecksh), gap G3.

---

### G4 — Every check needs me present (PRD invariant 2 / D1)

**Story:** As a user, no SSH session opens to my hosts unless I'm in the app
and have confirmed with biometric — nothing connects while my phone is locked
or in my pocket.

| AC | Status |
|----|--------|
| Each check/action session is preceded by a biometric confirmation | ❌ (the Keystore key requires one per use, but the signing path never drives the prompt — gap G2, **verify on a device**) |
| No scheduled/background code opens SSH sessions | ❌ (`CheckWorker` and `LogDigestWorker` still do — gap G1) |
| Devices that can't make a biometric-bound key are refused or clearly warned | ❌ (today: silent software-key fallback — open question Q2) |

**PRD reference:** [§1 invariant 2](../PRD.md#1-summary), gaps G1/G2.

---

## Epic H — Risk-gated check.sh suggestions

### H1 — Safe action runs immediately

**Story:** As a user, tapping "Apply" on a safe suggestion (e.g. tail the
journal) runs it immediately, no confirmation needed, and is logged.

| AC | Status |
|----|--------|
| `CheckActionCatalog` classifies the action id as SAFE | ✅ |
| Runs via SSH immediately, no dialog | ✅ |
| Recorded to the audit log | ✅ |

**Phase:** [Phase D](PHASES.md#phase-d--risk-gated-suggestion-execution)

---

### H2 — Risky action requires biometric and actually works

**Story:** As a user, tapping "Apply" on a risky suggestion (e.g. clean up
old logs) shows me the literal command, requires a fresh biometric
confirmation, and then actually succeeds against my host.

| AC | Status |
|----|--------|
| Literal command shown before confirming | ✅ |
| Fresh biometric prompt required | ✅ |
| Runs via a privileged sudoers helper the agent user can actually invoke | ✅ (fixed by `/code-review` — the first version ran an unprivileged command that would always fail) |
| Helper independently re-validates the action id before running anything | ✅ |
| Success/failure recorded to the audit log | ✅ |

**Phase:** [Phase D](PHASES.md#phase-d--risk-gated-suggestion-execution)

**Known gap:** only one action (`cleanup_old_logs`) is currently RISKY;
extending the catalog with more risky actions requires adding both a
catalog entry and a matching case in the `stackward-check-action` sudoers
helper.

---

### H3 — Scary or unrecognized action is manual-only

**Story:** As a user, the app never automates a boundary-changing action
(e.g. disabling SSH password auth) or one it doesn't recognize — it shows me
the exact manual steps instead.

| AC | Status |
|----|--------|
| SCARY actions (e.g. `disable_ssh_password_auth`) show a manual workaround, never execute | ✅ |
| An action id not in the catalog is treated as UNKNOWN — same manual-only treatment | ✅ |

**Phase:** [Phase D](PHASES.md#phase-d--risk-gated-suggestion-execution)

---

### H4 — The phone decides risk, not the host

**Story:** As a user, I'm protected even if check.sh itself is compromised
or buggy and mislabels a dangerous action as safe.

| AC | Status |
|----|--------|
| A suggestion's self-reported `risk` field is never used for gating | ✅ |
| `CheckSuggestionGate` resolves purely from the phone's own `CheckActionCatalog` | ✅ |
| Unit-tested: host claiming "safe" for an unknown or actually-risky action id still gets gated correctly | ✅ (`CheckSuggestionGateTest`) |

**PRD reference:** [§8 risk table](../PRD.md#8-risks--mitigations) — "Check
script is compromised or returns malicious suggestions."

---

## Epic I — Polling & notifications *(shipped; to be removed — PRD D1, gap G1)*

These stories describe behavior that exists in the code but that the revised
product no longer allows: background checks open SSH sessions with the user
absent. Once G1 lands, delete this epic (keeping I3's panic-revoke guarantee,
which shrinks to "wipes everything local") and mark the dogfood items below
as N/A.

### I1 — Per-host polling

**Story:** As a user, I choose per host whether it's checked only when I
tap, or automatically every 4 hours.

| AC | Status |
|----|--------|
| Manual / Auto-4h toggle per host | ✅ |
| Auto mode schedules a `CheckWorker` (WorkManager periodic job) | ✅ |
| Switching back to manual cancels the scheduled job | ✅ |

**Phase:** [Phase C](PHASES.md#phase-c--dashboard--per-host-polling)

---

### I2 — Critical-issue notification

**Story:** As a user with auto-polling enabled, I get notified if a
scheduled background check finds something critical — but not spammed for
routine checks or manual ones.

| AC | Status |
|----|--------|
| Notification fires only when the result contains a CRITICAL-severity issue | ✅ |
| Fires from scheduled `CheckWorker` runs only, not manual "check now" | ✅ |
| Missing `POST_NOTIFICATIONS` permission degrades silently — checks still run | ✅ |
| Permission requested when a host is first switched to Auto-4h | ✅ |

**Phase:** [Phase F](PHASES.md#phase-f--notifications)

---

### I3 — Panic revoke clears check.sh state too

**Story:** As a user hitting the panic-revoke button, I expect *everything*
wiped — including per-host check.sh results and any scheduled background
polling, not just credentials.

| AC | Status |
|----|--------|
| Every host's `CheckResultStore` entry cleared | ✅ (fixed by `/code-review` — previously left behind) |
| Every host's scheduled `CheckWorker` job cancelled | ✅ (fixed by `/code-review` — previously kept firing indefinitely) |
| Existing credential/profile/audit wipe still happens | ✅ |

**Phase:** [Phase F](PHASES.md#phase-f--notifications)

---

## Epic J — Optional on-device summary

### J1 — Summarize a host's issues

**Story:** As a user with a Gemma model imported, I can get a plain-English
summary of a host's current issues and suggestions.

| AC | Status |
|----|--------|
| "Summarize with Gemma" button on host detail | ✅ |
| Reuses the existing `LogSummarizer` pipeline unchanged | ✅ |
| Ignores model-proposed actions — text summary only, no new execution path | ✅ (deliberate) |

**Phase:** [Phase E](PHASES.md#phase-e--optional-gemma-summary)

---

### J2 — Graceful degradation without a model

**Story:** As a user without a model imported, the app still shows me
everything useful — it just doesn't offer a natural-language summary.

| AC | Status |
|----|--------|
| No model imported → explains why, structured list still fully visible | ✅ |
| Inference failure → shows the error, same degradation | ✅ |
| Stale summary cleared when the host is re-checked (never shows a summary for results no longer on screen) | ✅ |

**Phase:** [Phase E](PHASES.md#phase-e--optional-gemma-summary)

---

## Legacy: Log/Gemma Subsystem Stories

Pre-dates the Lookout pivot. Onboarding (Epic A) is shared with the
dashboard's "add host" flow; Epics B–F describe the Logs-screen-only path,
still functional but no longer primary.

### Epic A — Onboarding & identity (shared)

| Story | AC | Status |
|-------|-----|--------|
| A1 — Prep guidance | Prep screen shows `sudo adduser stackward-agent`; app doesn't create the OS user itself | ✅ |
| A2 — Password login + key install | Device public key lands in `authorized_keys`; bootstrap password (or private key) wiped after | ✅ |
| A3 — Elevated account acknowledgment | Root/passwordless-sudo detected; UI blocks until acknowledged; noted in audit trail | ✅ |
| A4 — Jump-host connect | Jump host fields in onboarding; per-hop TOFU; bastion never saved as a monitored profile | ✅ |
| A5 — Proxmox token import | Token stored in Keystore with biometric gate; admin runs `bootstrap_proxmox.sh` out-of-band | ✅ |
| A6 — Host-type auto-detection | Probe distinguishes proxmox/docker/linux; manual override in Settings | ⚠️ (auto-detect done, override ❌) |

### Epic B — Legacy monitoring & digests

| Story | AC | Status |
|-------|-----|--------|
| B1 — Hourly background fetch | WorkManager fetches journal/Docker/Proxmox hourly; heuristic anomaly flags | ✅ (shipped; to be removed with the other background SSH — G1) |
| B2 — Digest visible in app | Digest tab shows last fetch time + content | ✅ |
| B3 — Push notification on anomalies | Notification when heuristic flags fire | 🔮 (dropped — notifications need background SSH, ruled out by PRD D1) |
| B4 — Reconnect / backoff | SSH retry + WorkManager backoff on failure | ✅ |

### Epic C — Legacy AI-assisted investigation

| Story | AC | Status |
|-------|-----|--------|
| C1 — Import Gemma model | User import flow; E2B/E4B auto-select by device RAM | ✅ |
| C2 — Summarize loaded logs | Summarize button; degrades to raw logs with no model | ✅ |
| C3 — Natural-language question | Question field feeds `summarizeCurrentLogs(userQuestion)` | ✅ |
| C4 — Container health context | `docker inspect` health/status included when available | ✅ |

### Epic D — Legacy permission engine

| Story | AC | Status |
|-------|-----|--------|
| D1 — Routine read-only execution | Auto-executes, logged | ✅ |
| D2 — One-timer confirmation + biometric | Literal command shown; biometric required; `stackward-onetimer` helper | ✅ |
| D3 — Boundary-change draft-only | Never auto-executed; draft diff shown | ✅ |
| D4 — Proxmox power actions | Classified One-timer; config/allocation blocked | ✅ |
| D5 — Capability pack | v1 is Monitor-only — see `CapabilityPack.kt`; broader packs deferred, replaced in spirit by Epic H's risk-based gate for the Lookout flow | ✅ (narrowed, not removed) |

### Epic E — Security & recovery (shared)

| Story | AC | Status |
|-------|-----|--------|
| E1 — Panic revoke (in-app) | Biometric-gated; wipes credentials, profiles, **and now check.sh state** (Epic I3) | ✅ |
| E2 — Lost-phone admin revoke | Documented admin procedure via `stackward-panic-revoke` | ✅ |
| E3 — Key rotation | Push new key, verify, revoke old marker | ✅ |
| E4 — Audit log export | JSON export from Settings | ✅ |
| E5 — Tier 1 rule review reminder | 30-day reminder; sync from server sudoers snapshot | ✅ |
| E6 — Host key change recovery | TOFU rejects changed keys; user-facing re-pin flow | ⚠️ (reject ✅, re-pin UI ❌) |

### Epic F — Legacy settings & policy

| Story | AC | Status |
|-------|-----|--------|
| F1 — Capability pack selector | See D5 | ✅ (Monitor-only) |
| F2 — Elevated-identity ack in audit | Recorded at onboarding | ✅ |
| F3 — Docker log ACL default | File ACL by default; docker-group opt-in toggle | ⚠️ (ACL ✅, in-app toggle ❌) |

---

## Identity naming (canonical)

| Context | Canonical name |
|---------|----------------|
| Linux SSH user | `stackward-agent` |
| Proxmox API user | `stackward-agent@pve` |
| Proxmox custom role | `stackward-agent` |
| API token name | `stackward` → `stackward-agent@pve!stackward` |
| Sudoers file | `/etc/sudoers.d/stackward-agent` |
| SSH key comment | `stackward-agent-ssh` |
| RISKY check.sh suggestion helper | `/usr/local/sbin/stackward-check-action` |
| Legacy Tier 2 helper | `/usr/local/sbin/stackward-onetimer` |

Legacy `gemma-agent` references have been removed; use the canonical names above.

---

## Dogfood exit checklist

Minimum stories that must pass on **Pixel 8 + live Linux/Proxmox/Docker host**
before calling v1 production-ready. Lookout stories first — they're the
primary flow; legacy stories are secondary since that subsystem already
shipped a dogfood round before the pivot.

**First, on the phone (blocks everything else):**
- [ ] **G4 / gap G2** — A manual "Check now" on a real host actually completes over SSH with the per-use biometric Keystore key on Android 13+ (and see what happens on a device without one). If this fails, fix it before anything below.

**Lookout (primary):**
- [ ] **G1/G2** — Dashboard shows real hosts; host detail shows real issues/suggestions
- [ ] **H1** — Safe suggestion runs and is logged
- [ ] **H2** — Risky suggestion (`cleanup_old_logs`) succeeds end-to-end against a real host, not just in code review
- [ ] **H3** — Scary suggestion shown as manual workaround, never executes
- [ ] **G3** — *(after D2 is built)* A host onboarded through the app can be checked without ever running the admin script
- [ ] **I3** — Panic revoke wipes everything local; while auto-polling still exists, it also stops the scheduled `CheckWorker` job (verify in `adb shell dumpsys jobscheduler` or WorkManager's own inspection)
- ~~**I1** Auto-4h polling fires on schedule · **I2** critical issue produces a notification~~ — N/A: these features are being removed (PRD D1); instead confirm after G1 that nothing connects to a host in the background
- [ ] **J1/J2** — Gemma summary works with a model imported; degrades cleanly without one

**Legacy (secondary — re-verify nothing regressed):**
- [ ] **A2** — Full onboarding still works from the dashboard's "+" button
- [ ] **D2** — Legacy Tier 2 service restart end-to-end via the Logs screen
- [ ] **E1** — Panic revoke tested on a throwaway host

**Stretch (v1.1):** A6 host-type override, E6 re-pin UI, F3 docker-group
in-app toggle, additional RISKY actions in the check.sh catalog (restart a
service, reboot, package updates — each needs a matching
`stackward-check-action` case and a design for validating any parameter; see
[PRD §5.2](../PRD.md#52-risk-based-action-gating)).

---

## Future epic (out of v1 scope)

**K — Multi-host profiles at scale:** the dashboard handles a homelab's worth
of hosts (5–20); nothing has been tested or tuned for substantially larger
fleets.
