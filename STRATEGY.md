# Strategy: Stackward as a Personal Lookout

**Date:** 2026-09-24  
**Status:** Chosen north star (replaces mixed identity from earlier phases)

## The Thesis

Stackward is a **read-only triage instrument** for a single operator managing their own infrastructure from a phone **without opening a laptop or exposing logs to a third-party SaaS.**

The job: **"Is something wrong right now? Should I open the laptop?"**

Not: "I SRE the cluster from my phone" or "I'm a small-business operator with team delegation."

## What This Means

### In Scope (v1 & beyond)

- **Detect anomalies** — Read logs, check system metrics, run heuristics to flag errors, 5xx codes, OOM, security risks
- **Optional Gemma summarization** — Natural-language triage summary, user-imported Gemma 2B/4B
- **Risk-based improvements** — Phone gates suggested actions:
  - **Safe** (restart service, check logs): auto-approve, logged
  - **Risky** (disable SSH password auth, update packages): require biometric confirmation
  - **Scary** (edit sudoers, change Proxmox permissions): forbidden, documented manual path
- **Correspondence tempo** — Check every N hours on schedule
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
| **Safe** | Restart service, check logs, verify config | Auto-approve, logged |
| **Risky** | Disable SSH password auth, update packages, reboot host | Biometric confirmation required |
| **Scary** | Edit sudoers, modify Proxmox permissions, change SSH port | Forbidden; documented manual workaround |

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

**Primary:** Solo homelab operators (Proxmox + Docker, ~100–1000 nodes), who want a low-friction triage path from their phone.

**Secondary:** No small-business operator story in v1. No team story. README clarifies MIT license and "not ready for contribution."

**Not:** Pulse Patrol users (they already have NL + actuation), Termius users (they already have SSH), or Home Assistant users looking for an additional dashboard.

## Success Metrics

1. **Build:** Debug APK assembles without model, with Gemma, and with alternative Gemma versions (under 10 min on a dev machine).
2. **First digest:** From "enter IP + SSH key" to "I see the first heuristic anomaly" in under five minutes.
3. **Gemma path:** Model import flow works; summarization happens in under 30s on a 2026 flagship.
4. **Dogfood:** The author actually uses it weekly on their own cluster, without opening a laptop.

## Implementation Order

1. **Freeze Maintain/Provision** — Mark as v2+ in CapabilityPack; remove from UI.
2. **Emphasize read-only** — PRD rewrites, docs updates.
3. **Test no-model mode** — Verify heuristic digests work without Gemma.
4. **Integrate Gemma as optional** — Make summarization a clearly optional extra.
5. **Simplify install story** — APK + SSH key = first value (no bootstrap required for Monitor tier).

## Relationship to Ideation Doc (2026-09-15)

This strategy resolves the "three mutually exclusive theses" in `docs/ideation/2026-09-15-stackward-scope-aim-ideation.html`:

- **Rejected: Companion** — "phone gates, brain on LAN" — requires host inference service; real value is the signature ritual, not the inference quality. Saved for if Lookout + Gemma hit accuracy ceiling.
- **Chosen: Lookout** — "read-only triage, model optional" — reuses existing heuristic detection, solves the real job, ships to N=1 without Play Store enrollment.
- **Rejected: Notary** — "closed verbs, biometric gates" — solves the signature ritual but not the triage job; would be a different product.

Ideation ideas 5 & 6 (2026-installable client, N=1 MIT success) are embedded in this strategy and become shipping requirements, not options.
