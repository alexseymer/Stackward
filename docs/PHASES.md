# Stackward — Build Phases

Recommended build order: **0/1 → 4 → 2/3 → 5**

Each phase is independently testable. Do not skip ahead — later phases depend
on earlier ones being solid.

## Current stage

**All phases (0/1 → 5) are feature-complete at scaffold level** in the
`0.5.4-dogfood` build, including Proxmox API integration. Every task table below
is ✅ Done. The project is **not production-ready** — current focus is dogfooding
on real hardware and stabilization rather than net-new phases (see
[Beyond Phase 5](#beyond-phase-5--current-focus)).

| Phase | Focus | State |
|-------|-------|-------|
| 0 / 1 | Bootstrap & credential provisioning (SSH + Proxmox, jump-host) | ✅ Done |
| 2 | On-device Gemma integration | ✅ Done |
| 3 | Tiered permission engine | ✅ Done |
| 4 | MVP: unified log reading | ✅ Done |
| 5 | Hardening | ✅ Done |

---

## Phase 0 — Bootstrap Problem

**Goal:** Solve the chicken-and-egg of provisioning a restricted user when no
restricted key exists yet.

| Task | Status |
|------|--------|
| Onboarding screen: IP/port + one-time admin credential | Done (Compose UI + admin user) |
| Show bootstrap script to user before execution (auditability) | Done (script preview step) |
| Run `scripts/bootstrap_linux.sh` over admin SSH session | Done |
| Discard admin credential after successful bootstrap (never store) | Done |
| Detect host type: plain Linux / Proxmox / Docker (probe or user-declared) | Done (SSH probe) |

**Exit criteria:** User can enter an IP and end up with a `gemma-agent` user
on the target host, without manual server-side steps.

---

## Phase 1 — Key & User Provisioning

**Goal:** Automated, secure credential lifecycle on the phone.

| Task | Status |
|------|--------|
| `AgentKeyManager`: generate ed25519 keypair in Android Keystore | Done (API 33+ Keystore, software fallback API 28–32) |
| `setUserAuthenticationRequired(true)` — biometric per signing op | Done (Keystore path; biometric on use) |
| Push public key to `authorized_keys` with `command=` restrictions | Done (via bootstrap) |
| Verify restricted connection before discarding admin access | Done |
| Host key pinning (TOFU) + change alerting | Done (pin store + verifier) |
| Jump-host support: provision agent on bastion, tunnel to internal hosts | Done (onboarding UI + bastion relay provision + per-hop TOFU) |
| Proxmox: run `scripts/bootstrap_proxmox.sh`, store scoped API token | Done (auto during onboarding + biometric store) |

**Exit criteria:** App connects to target using Keystore key only; admin
credential is gone; host key is pinned.

---

## Phase 4 — MVP: Unified Log Reading

**Goal:** First real feature. Read-only, zero elevation, validates the full
pipeline before any write capability exists.

> **Build this before Phase 2/3.** It proves inference → tool call → SSH/API →
> response loop with zero blast radius.

| Task | Status |
|------|--------|
| `journalctl` access via `systemd-journal` group (from bootstrap) | Done |
| Docker log read via file ACL (not docker group) | Done |
| Proxmox: VM/LXC status + task log via scoped API token | Done (SSH tunnel + API digest) |
| Pre-filter / truncate output before sending to model | Done (32k char limit) |
| On-demand log query UI | Done (Journal / Docker / Digest tabs) |
| Scheduled hourly digest (no confirmation, read-only) | Done (WorkManager) |
| Reconnect-with-backoff for unattended digests | Done (WorkManager exponential backoff + SSH retry) |

**Exit criteria:** User gets an hourly digest of anomalies across all three
log sources; can ask "what's wrong with container X" and get a summary.

---

## Phase 2 — Local Model Integration

**Goal:** Run Gemma 4 on-device for summarization and structured proposals.

| Task | Status |
|------|--------|
| Bundle or download Gemma 4 E2B (quantized) via MediaPipe LLM Inference API | Done (user import flow) |
| Device capability check (RAM, NPU/GPU) on first launch | Done (RAM-based E2B/E4B) |
| Auto-select E2B vs E4B based on device | Done |
| Structured output schema for tool proposals (JSON / function-calling) | Done (ActionProposalParser) |
| Graceful degradation: no model → no AI summarization (no cloud fallback) | Done |

**Exit criteria:** Model summarizes log output and emits structured Tier 1/2
proposals that the permission engine can parse.

---

## Phase 3 — Tiered Permission Engine

**Goal:** Enforce the three-tier safety model for mutating actions.

| Task | Status |
|------|--------|
| `PermissionEngine`: classify proposals into Tier 1/2/3 | Done |
| Tier 1: match against `sudoers.d/gemma-agent` rules, log + execute | Done |
| Tier 2: confirmation UI (literal command + reason) + biometric | Done |
| Tier 2: temporary single-use sudoers grant (write → execute → delete) | Done (`stackward-onetimer` helper) |
| Tier 3: draft-only path for sudoers / Proxmox role changes | Done |
| Proxmox API backend: map tiers to token permissions | Done (read Tier 1, power Tier 2, config blocked Tier 3) |
| Full audit log (command, tier, approval, output, timestamp) | Done |

**Exit criteria:** User can approve a one-time service restart; Tier 3 changes
are blocked from automated execution.

> **Re-bootstrap note:** Hosts provisioned before Phase 3 need a fresh bootstrap
> (or manual install of `/usr/local/sbin/stackward-onetimer`) for Tier 2.

---

## Phase 5 — Hardening

**Goal:** Production-readiness for daily use.

| Task | Status |
|------|--------|
| Audit log export / optional sync to durable storage | Done (JSON export via Settings) |
| Key rotation flow (regenerate + push new key, revoke old) | Done |
| Panic revoke: one tap removes `authorized_keys` entry | Done |
| Tier 1 rule review UI (periodic reminder to audit sudoers.d) | Done (30-day reminder + server sync) |
| Multi-hop host key pinning verification | Done (SSHJ jump + per-hop TOFU) |
| Network change / tunnel drop recovery | Done (SSH retry + WorkManager backoff) |

**Exit criteria:** Lost phone scenario is recoverable; audit trail is complete;
operator can rotate credentials without re-bootstrap.

> **Re-bootstrap note:** Hosts provisioned before Phase 5 need a fresh bootstrap
> (or manual install of `stackward-push-key`, `stackward-revoke-key`,
> `stackward-panic-revoke`, `stackward-sudoers-snapshot`) for hardening helpers.

---

## Dependency Graph

```
Phase 0 ──▶ Phase 1 ──▶ Phase 4 (MVP)
                │              │
                │              ▼
                └──────▶ Phase 2 ──▶ Phase 3 ──▶ Phase 5
```

Phase 4 can start as soon as Phase 1 provides a working SSH connection —
it does not require the on-device model (log fetching + display works without
AI; summarization layers on in Phase 2).

---

## Beyond Phase 5 — current focus

With all phases feature-complete at scaffold level, work now shifts from building
new phases to hardening the existing ones through real-world use:

- **Dogfooding on real hardware** — validate the full loop (bootstrap → log digest
  → Tier 2 confirmation → audit) against live Linux/Proxmox/Docker hosts and a
  physical device running Gemma.
- **Model integration polish** — tune E2B/E4B selection, prompt/context budgeting,
  and proposal-parsing robustness against real model output.
- **Stability & recovery** — exercise reconnect/backoff, tunnel-drop recovery, and
  host-key-change alerting under flaky-network conditions.
- **Test coverage** — grow the JVM unit suite around the permission engine, proposal
  parser, and Proxmox command gating; add instrumented tests where feasible.
- **Toward production-ready** — resolve the [Open Decisions in the PRD](../PRD.md#10-open-decisions)
  and close the exit-criteria gaps observed during dogfooding.
