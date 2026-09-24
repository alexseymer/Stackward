# Strategy: Stackward as a Personal Lookout

**Date:** 2026-09-24  
**Status:** Chosen north star (replaces mixed identity from earlier phases)

## The Thesis

Stackward is a **read-only triage instrument** for a single operator managing their own infrastructure from a phone **without opening a laptop or exposing logs to a third-party SaaS.**

The job: **"Is something wrong right now? Should I open the laptop?"**

Not: "I SRE the cluster from my phone" or "I'm a small-business operator with team delegation."

## What This Means

### In Scope (v1 & beyond)

- **Read-only logs** — systemd journal, Docker container logs, Proxmox task logs
- **Status snapshots** — VM/LXC states, container health, disk/memory via Proxmox API
- **Heuristic digests** — fast offline detection of errors, 5xx codes, OOM, connection failures
- **Optional Gemma summarization** — natural-language triage, user-imported Gemma 2B/4B
- **Correspondence tempo** — check every N hours, answer the "should I act" question
- **Jump-host support** — monitoring through SSH tunnels (required for most homelabs)

### Out of Scope (v1 onward)

- **Maintain phase** — one-off restarts, service state changes (Tier 2 in PRD, archived)
- **Provision phase** — VM/LXC creation, cluster changes (Tier 3 in PRD, archived)
- **Autonomous actions** — if any future work permits actuation, it is not Lookout
- **Team/multi-device** — this is a personal instrument

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

### Capability Model (simplified)

| Tier | What | Status |
|------|------|--------|
| **Tier 1 — Read** | Logs, status, heuristic digests, optional Gemma summary | v1, always on |
| **Tier 2 — Maintain** | One-time restarts, service actions | Archived; never v1 |
| **Tier 3 — Provision** | VM/LXC creation, sudoers changes | Archived; never v1 |

### Deliverable

An installable Android APK that:
- Connects to a user's Linux host, Proxmox cluster, or Docker daemon via SSH
- Imports their choice of Gemma (2B/4B quantized, or none)
- Shows a glanceable feed of "what's wrong right now" with heuristic + optional NL
- Requires no Play Store, no cloud, no team setup
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
