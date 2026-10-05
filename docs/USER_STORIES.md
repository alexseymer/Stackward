# Stackward — User Stories & Acceptance Criteria

Detailed acceptance criteria for dogfood testing. High-level outcomes live in
[PRD.md §6](../PRD.md#6-key-user-stories).

**Status key:** ✅ implemented · ⚠️ partial / scaffold · ❌ not yet · 🔮 v1.1 stretch

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

## Epic I — Polling & notifications

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
| A2 — Password login + key install | Device public key lands in `authorized_keys`; bootstrap password wiped after | ✅ |
| A3 — Elevated account acknowledgment | Root/passwordless-sudo detected; UI blocks until acknowledged; noted in audit trail | ✅ |
| A4 — Jump-host connect | Jump host fields in onboarding; per-hop TOFU; bastion never saved as a monitored profile | ✅ |
| A5 — Proxmox token import | Token stored in Keystore with biometric gate; admin runs `bootstrap_proxmox.sh` out-of-band | ✅ |
| A6 — Host-type auto-detection | Probe distinguishes proxmox/docker/linux; manual override in Settings | ⚠️ (auto-detect done, override ❌) |

### Epic B — Legacy monitoring & digests

| Story | AC | Status |
|-------|-----|--------|
| B1 — Hourly background fetch | WorkManager fetches journal/Docker/Proxmox hourly; heuristic anomaly flags | ✅ |
| B2 — Digest visible in app | Digest tab shows last fetch time + content | ✅ |
| B3 — Push notification on anomalies | Notification when heuristic flags fire | 🔮 (superseded by Epic I's critical-issue notifications for the Lookout flow) |
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

**Lookout (primary):**
- [ ] **G1/G2** — Dashboard shows real hosts; host detail shows real issues/suggestions
- [ ] **H1** — Safe suggestion runs and is logged
- [ ] **H2** — Risky suggestion (`cleanup_old_logs`) succeeds end-to-end against a real host, not just in code review
- [ ] **H3** — Scary suggestion shown as manual workaround, never executes
- [ ] **I1** — Auto-4h polling actually fires on schedule
- [ ] **I2** — Critical issue on a real host produces a real notification
- [ ] **I3** — Panic revoke actually stops a scheduled `CheckWorker` job (verify in `adb shell dumpsys jobscheduler` or WorkManager's own inspection)
- [ ] **J1/J2** — Gemma summary works with a model imported; degrades cleanly without one

**Legacy (secondary — re-verify nothing regressed):**
- [ ] **A2** — Full onboarding still works from the dashboard's "+" button
- [ ] **D2** — Legacy Tier 2 service restart end-to-end via the Logs screen
- [ ] **E1** — Panic revoke tested on a throwaway host

**Stretch (v1.1):** A6 host-type override, E6 re-pin UI, F3 docker-group
in-app toggle, additional RISKY actions in the check.sh catalog.

---

## Future epic (out of v1 scope)

**K — Multi-host profiles at scale:** the dashboard handles a homelab's worth
of hosts (5–20); nothing has been tested or tuned for substantially larger
fleets.
