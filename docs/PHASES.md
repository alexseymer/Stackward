# Stackward — Build Phases

Recommended build order: **0/1 → 4 → 2/3 → 5**

Each phase is independently testable. Do not skip ahead — later phases depend
on earlier ones being solid.

---

## Phase 0 — Bootstrap Problem

**Goal:** Solve the chicken-and-egg of provisioning a restricted user when no
restricted key exists yet.

| Task | Status |
|------|--------|
| Onboarding screen: IP/port + one-time admin credential | TODO |
| Show bootstrap script to user before execution (auditability) | TODO |
| Run `scripts/bootstrap_linux.sh` over admin SSH session | TODO |
| Discard admin credential after successful bootstrap (never store) | TODO |
| Detect host type: plain Linux / Proxmox / Docker (probe or user-declared) | TODO |

**Exit criteria:** User can enter an IP and end up with a `gemma-agent` user
on the target host, without manual server-side steps.

---

## Phase 1 — Key & User Provisioning

**Goal:** Automated, secure credential lifecycle on the phone.

| Task | Status |
|------|--------|
| `AgentKeyManager`: generate ed25519 keypair in Android Keystore | Done (API 33+ Keystore, software fallback API 28–32) |
| `setUserAuthenticationRequired(true)` — biometric per signing op | Done (Keystore path; biometric on use) |
| Push public key to `authorized_keys` with `command=` restrictions | TODO |
| Verify restricted connection before discarding admin access | TODO |
| Host key pinning (TOFU) + change alerting | TODO |
| Jump-host support: provision agent on bastion, tunnel to internal hosts | TODO |
| Proxmox: run `scripts/bootstrap_proxmox.sh`, store scoped API token | TODO |

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
| `journalctl` access via `systemd-journal` group (from bootstrap) | TODO |
| Docker log read via file ACL (not docker group) | TODO |
| Proxmox: VM/LXC status + task log via scoped API token | TODO |
| Pre-filter / truncate output before sending to model | TODO |
| On-demand log query UI | TODO |
| Scheduled hourly digest (no confirmation, read-only) | TODO |
| Reconnect-with-backoff for unattended digests | TODO |

**Exit criteria:** User gets an hourly digest of anomalies across all three
log sources; can ask "what's wrong with container X" and get a summary.

---

## Phase 2 — Local Model Integration

**Goal:** Run Gemma 4 on-device for summarization and structured proposals.

| Task | Status |
|------|--------|
| Bundle or download Gemma 4 E2B (quantized) via MediaPipe LLM Inference API | TODO |
| Device capability check (RAM, NPU/GPU) on first launch | TODO |
| Auto-select E2B vs E4B based on device | TODO |
| Structured output schema for tool proposals (JSON / function-calling) | TODO |
| Graceful degradation: no model → no AI summarization (no cloud fallback) | TODO |

**Exit criteria:** Model summarizes log output and emits structured Tier 1/2
proposals that the permission engine can parse.

---

## Phase 3 — Tiered Permission Engine

**Goal:** Enforce the three-tier safety model for mutating actions.

| Task | Status |
|------|--------|
| `PermissionEngine`: classify proposals into Tier 1/2/3 | TODO |
| Tier 1: match against `sudoers.d/gemma-agent` rules, log + execute | TODO |
| Tier 2: confirmation UI (literal command + reason) + biometric | TODO |
| Tier 2: temporary single-use sudoers grant (write → execute → delete) | TODO |
| Tier 3: draft-only path for sudoers / Proxmox role changes | TODO |
| Proxmox API backend: map tiers to token permissions | TODO |
| Full audit log (command, tier, approval, output, timestamp) | TODO |

**Exit criteria:** User can approve a one-time service restart; Tier 3 changes
are blocked from automated execution.

---

## Phase 5 — Hardening

**Goal:** Production-readiness for daily use.

| Task | Status |
|------|--------|
| Audit log export / optional sync to durable storage | TODO |
| Key rotation flow (regenerate + push new key, revoke old) | TODO |
| Panic revoke: one tap removes `authorized_keys` entry | TODO |
| Tier 1 rule review UI (periodic reminder to audit sudoers.d) | TODO |
| Multi-hop host key pinning verification | TODO |
| Network change / tunnel drop recovery | TODO |

**Exit criteria:** Lost phone scenario is recoverable; audit trail is complete;
operator can rotate credentials without re-bootstrap.

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
