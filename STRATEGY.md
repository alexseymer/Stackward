# Strategy: Stackward as a Personal Lookout

**Date:** 2026-09-24 (revised 2026-10-23 to match PRD decisions D1–D4)  
**Status:** Chosen north star (replaces mixed identity from earlier phases)

## The Thesis

Stackward is a **read-only triage instrument** for a single operator managing their own infrastructure from a phone **without opening a laptop or exposing logs to a third-party SaaS.**

The job: **"Is something wrong right now? Should I open the laptop?"**

Not: "I SRE the cluster from my phone" or "I'm a small-business operator with team delegation."

## What This Means

### In Scope (v1 & beyond)

- **Detect anomalies** — Read logs, check system metrics, run heuristics to flag errors, 5xx codes, OOM, security risks
- **Optional Gemma summarization** — Natural-language triage summary, user-imported Gemma 2B/4B
- **Risk-based improvements** — The phone's own catalog gates suggested actions:
  - **Safe** (read logs, list failed services): runs immediately, logged
  - **Risky** (clean up old logs): shows the command, requires biometric confirmation
  - **Scary** (disable SSH password auth, edit sudoers, change Proxmox permissions): never run, manual steps shown
- **On-demand tempo** — Checks run when you open a host and confirm with biometric; no background polling (PRD D1)
- **Jump-host support** — Monitor through SSH tunnels (required for most homelabs)

### Out of Scope (v1 onward)

- **Autonomous unattended actions** — everything gated by user confirmation
- **Broad provisioning** — VM/LXC creation, cluster changes
- **Team/multi-operator** — this is a personal instrument per deployment

### Why

1. **Need is real.** Homelab operators actually want this. The fire-lookout job is solved nowhere else.
2. **Realism.** The current install story (ADB + multi-GB model + root bootstrap) can be solved for read-only. It cannot be solved for "SRE from the pub" without Play Store enrollment and broader distribution infrastructure.
3. **Competitive wedge.** Pulse Patrol occupies "permissioned AI for Proxmox." Termius occupies "SSH from the phone." Lookout occupies "natural-language smoke detection, nothing to SaaS." This slot is uncontested.
4. **Sunk cost is not a reason.** Tier 2 confirmation UI and Proxmox Maintain packs are scaffolded but not shipped. Dropping them here is the correct move, not a loss.

## Architecture

### Invariants (unchanged)

1. **Inference stays on-device** — no prompts, logs, or command output leave the phone except over the user's own SSH.
2. **No third party sees infra** — all data stays on the device and user-owned hosts.
3. **Model is optional** — if no Gemma `.task` is imported, heuristic digests work without it.

### Action Gating Model (simplified)

| Risk Level | What | Gate |
|------------|------|------|
| **Safe** | Read-only: tail journal, list failed services | Auto-approve, logged |
| **Risky** | Recoverable state change needing elevation: clean up old logs | Biometric confirmation required |
| **Scary** | Access/boundary changes: disable SSH password auth, edit sudoers, modify Proxmox permissions | Forbidden; documented manual workaround |

**Why this over "Tiers":** Old tier language (Maintain/Provision) suggested capability packs. New model is risk-based: phone assesses the action and gates it. No capability switching; same model, different gates per action.

### Deliverable

An installable Android APK that:
- Connects to a user's infrastructure via SSH (one-time password → key install)
- Queries a lightweight check script (`~/.stackward/check.sh`) for structured anomalies
- Optionally runs user-imported Gemma (2B/4B quantized) for NL summaries
- Gates improvements by risk: safe auto-approve, risky require biometric, scary forbidden
- Requires no Play Store, no cloud, no team setup, no broad host elevation by default
- Works on N=1 (the author using it on their own cluster)

## Audience

**Primary:** Solo homelab operators (Linux / Proxmox / Docker, typically a handful to a few dozen hosts), who want a low-friction triage path from their phone.

**Secondary:** No small-business operator story in v1. No team story. README clarifies MIT license and "not ready for contribution."

**Not:** Pulse Patrol users (they already have NL + actuation), Termius users (they already have SSH), or Home Assistant users looking for an additional dashboard.

## Success Metrics

1. **Build:** Debug APK assembles without model, with Gemma, and with alternative Gemma versions (under 10 min on a dev machine).
2. **First digest:** From "enter IP + SSH key" to "I see the first heuristic anomaly" in under five minutes.
3. **Gemma path:** Model import flow works; summarization happens in under 30s on a 2026 flagship.
4. **Dogfood:** The author actually uses it weekly on their own cluster, without opening a laptop.

## Implementation Specifics (v1 Refined)

### Check Script Monitoring

`~/.stackward/check.sh` detects and reports on (exact checks and thresholds: PRD §5.1):
- **Logs:** error-priority systemd journal entries
- **Disk & Memory:** filesystem % (85 high / 95 critical), memory % (80)
- **Service Health:** failed systemd units
- **Security Basics:** SSH password auth enabled, number of listening ports
- **Docker:** exited containers

Candidates, not yet covered: Proxmox task logs, Docker log contents, swap,
firewall state, restart counts. Returns JSON with issues (detected problems)
and suggestions (proposed actions). The app installs it during onboarding
(PRD D2).

### Action Risk Levels

| Risk | Actions | Gate | Examples |
|------|---------|------|----------|
| **Safe** | Read-only operations | Auto-approve, logged | Tail journal, list failed services |
| **Risky** | Recoverable state changes needing elevation | Biometric required | Clean up old logs (candidates: restart a service, reboot, package updates) |
| **Scary** | Access or system-boundary changes | Forbidden, manual workaround shown | Disable SSH password auth, edit sudoers, change Proxmox perms, modify SSH port |

The phone-side `CheckActionCatalog` is the source of truth for this list (PRD §5.2).

### Phone App UI & Behavior

- **Dashboard:** Display all configured hosts at once; tap to see issues + suggestions for one host
- **Checks:** On demand only — "Check now" plus biometric; the dashboard shows each host's last result and its age. No background polling and no notifications (PRD D1: every SSH session needs the user present).
- **Actions:** User taps "Apply" → if safe, runs immediately; if risky, requires biometric + confirms; if scary, shows manual step

### Gemma Integration

- Optional: user imports Gemma 2B/4B `.task`/`.litertlm` file once
- On-demand summarization: phone runs inference on anomalies, displays summary
- Without Gemma: structured anomalies still work; no NL summary
- Fallback: if Gemma inference fails (timeout, OOM), show structured list

## Implementation Order

All six steps below are implemented on `claude/stackward-github-review-67l3hd`
as of 2026-09-26 — build-verified (`:app:testDebugUnitTest`,
`:app:assembleDebug`, `:app:lintDebug` all green) and past two review passes
(`/simplify` for reuse/efficiency, `/code-review` for correctness — two real
bugs found and fixed: a RISKY suggestion's missing privilege escalation, and
panic-revoke not clearing per-host check.sh state). Not yet dogfooded against
real infrastructure or a physical device — see docs/PHASES.md.

**Revised 2026-10-23 (PRD D1, D2):** step 3's per-host polling toggle and step
6 (notifications) are to be removed, because they require SSH with no user
present. Step 2's `check.sh` delivery moves into app onboarding. The open work
is tracked in PRD §11.

1. ✅ **Check script core** — `scripts/check.sh`: log/disk/service/security detection, JSON output
2. ✅ **Bootstrap integration** — `scripts/bootstrap_linux.sh` embeds `check.sh` verbatim, installs a dedicated sudoers helper (`stackward-check-action`) for the one RISKY suggestion that needs root
3. ✅ **Phone app refactor** — `dev.stackward.ui.dashboard`: multi-host dashboard, per-host detail screen, per-host polling toggle
4. ✅ **Risk-gated actions** — `dev.stackward.check.CheckActionCatalog`/`CheckSuggestionGate`: phone-side risk classification (never trusts the host's self-reported risk), biometric confirmation for risky, manual-only for scary/unknown
5. ✅ **Gemma optional layer** — Host detail screen reuses the existing `LogSummarizer` pipeline; degrades identically to the Logs screen (no model → explains why, inference failure → shows the error, structured issues/suggestions always visible)
6. ✅ **Notifications** — `CheckNotifier`: critical-issue-only, best-effort (missing POST_NOTIFICATIONS permission just means no alert, checks still run)

## Relationship to Ideation Doc (2026-09-15)

This strategy resolves the "three mutually exclusive theses" in `docs/ideation/2026-09-15-stackward-scope-aim-ideation.html`:

- **Rejected: Companion** — "phone gates, brain on LAN" — requires host inference service; real value is the signature ritual, not the inference quality. Saved for if Lookout + Gemma hit accuracy ceiling.
- **Chosen: Lookout** — "read-only triage, model optional" — reuses existing heuristic detection, solves the real job, ships to N=1 without Play Store enrollment.
- **Rejected: Notary** — "closed verbs, biometric gates" — solves the signature ritual but not the triage job; would be a different product.

Ideation ideas 5 & 6 (2026-installable client, N=1 MIT success) are embedded in this strategy and become shipping requirements, not options.
