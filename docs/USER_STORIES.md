# Stackward — User Stories & Acceptance Criteria

Detailed acceptance criteria for dogfood testing. High-level outcomes live in
[PRD.md §6](../PRD.md#6-key-user-stories).

**Status key:** ✅ implemented · ⚠️ partial / scaffold · ❌ not yet · 🔮 v1.1 stretch

---

## Epic A — Onboarding & identity

### A1 — Prep guidance

**Story:** As a user, I see how to create the recommended `stackward-agent` OS
account before connecting.

| AC | Status |
|----|--------|
| Prep screen shows `sudo adduser stackward-agent` (Debian/Ubuntu) | ✅ |
| Copy explains the app does not create the OS user — admin creates it out-of-band | ✅ |

**Phase:** [Phase 0](PHASES.md#phase-0--bootstrap-problem)

---

### A2 — One-time password login + ssh-copy-id-style key install

**Story:** As a user, I log in once with password and the app installs its SSH
public key into `~/.ssh/authorized_keys`, then wipes the password.

| AC | Status |
|----|--------|
| Given a reachable host and existing `stackward-agent` user, when I complete onboarding, then my device public key appears in `~stackward-agent/.ssh/authorized_keys` | ✅ |
| Remote install script prints `STACKWARD_KEY_INSTALLED=1` on success | ✅ |
| Bootstrap password is wiped from memory/storage after key install | ✅ |
| Post-setup SSH sessions use the username chosen at onboarding (stored in `ServerProfile`) | ✅ |

**Known gaps:** none for core key-install path after identity unification; heuristic
digest flags and NL query UI remain open (Epic B/C).

**Phase:** [Phase 0/1](PHASES.md#phase-1--key--user-provisioning)

---

### A3 — Elevated account detection + acknowledgment

**Story:** As a user, if I connect with root or passwordless sudo, the app warns
me and requires explicit acknowledgment before key install.

| AC | Status |
|----|--------|
| Login probe detects root and/or passwordless sudo | ✅ |
| UI blocks provision until acknowledgment checkbox/tap | ✅ |
| Elevated identity noted in audit trail | ⚠️ (F2) |

**Phase:** [Phase 0](PHASES.md#phase-0--bootstrap-problem)

---

### A4 — Jump-host / port-knock connect

**Story:** As a user, I reach LAN-only targets via a jump host (and optional
port knock) during onboarding.

| AC | Status |
|----|--------|
| Jump host fields in onboarding UI | ✅ |
| Agent key installed on bastion for ProxyJump auth | ✅ |
| Per-hop host key pinning (TOFU) | ✅ |
| Bastion is **not** saved as a monitored `ServerProfile` | ✅ |

**Phase:** [Phase 0/1](PHASES.md#phase-1--key--user-provisioning)

---

### A5 — Proxmox token import (out-of-band)

**Story:** As a user on a Proxmox host, I import a scoped API token created by
an admin via `scripts/bootstrap_proxmox.sh`.

| AC | Status |
|----|--------|
| App detects Proxmox host type after connect | ✅ |
| Token stored in Android Keystore with biometric gate | ✅ |
| Default Proxmox user is `stackward-agent@pve` with role `stackward-agent` | ✅ |
| App does not auto-create tokens (admin runs script) | ✅ |

**Phase:** [Phase 1](PHASES.md#phase-1--key--user-provisioning)

---

### A6 — Host-type auto-detection

**Story:** As a user, the app detects plain Linux / Proxmox / Docker after
connect without me declaring it upfront.

| AC | Status |
|----|--------|
| Probe distinguishes proxmox / docker / linux | ✅ |
| Optional manual override in Settings | ❌ (future) |

**PRD decision:** auto-detect; Settings override later.

---

## Epic B — Monitoring & digests

### B1 — Hourly background fetch

**Story:** As a user, the app fetches journal, Docker, and Proxmox data on a
schedule without me opening the app.

| AC | Status |
|----|--------|
| WorkManager runs hourly digest worker | ✅ |
| Journal: error-level entries from last hour | ✅ |
| Docker: container log tails (not just container IDs) | ⚠️ |
| Proxmox: task log via scoped API token (when configured) | ✅ |
| Heuristic anomaly flags (errors, failed tasks, restart loops) | ❌ |

**Clarification:** v1 "anomaly" means heuristic flags, not raw log dumps only.
Optional on-device AI summary when a Gemma model is loaded.

**Phase:** [Phase 4](PHASES.md#phase-4--mvp-unified-log-reading)

---

### B2 — Digest visible in app

**Story:** As a user, I open the Digest tab and see the latest aggregated
results.

| AC | Status |
|----|--------|
| Digest persisted locally between runs | ✅ |
| Digest tab shows last fetch time and content | ✅ |

---

### B3 — Push notification on anomalies

**Story:** As a user, I get notified when a digest contains flagged problems.

| AC | Status |
|----|--------|
| Android notification when heuristic flags fire | 🔮 v1.1 |

---

### B4 — Reconnect / backoff on network failure

**Story:** As a user, scheduled digests retry after transient network failures.

| AC | Status |
|----|--------|
| SSH retry with exponential backoff | ✅ |
| WorkManager backoff on worker failure | ✅ |

**Phase:** [Phase 4](PHASES.md#phase-4--mvp-unified-log-reading), [Phase 5](PHASES.md#phase-5--hardening)

---

## Epic C — AI-assisted investigation

### C1 — Import Gemma model

**Story:** As a user, I import a Gemma E2B/E4B model and the app selects the
variant based on device RAM.

| AC | Status |
|----|--------|
| User import flow (no bundled multi-GB model) | ✅ |
| E2B vs E4B auto-select | ✅ |
| Device capability check on first launch | ✅ |

**Phase:** [Phase 2](PHASES.md#phase-2--local-model-integration) · See [MODEL_SETUP.md](MODEL_SETUP.md)

---

### C2 — Summarize loaded logs

**Story:** As a user, I summarize the logs currently on screen; without a
model, the app shows raw logs only.

| AC | Status |
|----|--------|
| Summarize button invokes on-device inference | ✅ |
| Graceful degradation when no model imported | ✅ |

---

### C3 — Natural-language question

**Story:** As a user, I type a question like "why is container X unhealthy?"
and get an on-device summary tied to that question.

| AC | Status |
|----|--------|
| Question text field on Logs screen | ❌ |
| `summarizeCurrentLogs(userQuestion)` receives the question | ⚠️ (API exists, UI missing) |

---

### C4 — Container health context

**Story:** As a user asking about a container, the summary includes health
status and recent logs, not logs alone.

| AC | Status |
|----|--------|
| `docker inspect` health/status included in context | ❌ |
| Recent json-log tail for selected container | ✅ |

---

## Epic D — Safe actions (permission engine)

### D1 — Tier 1 read-only execution

**Story:** As a user, pre-vetted read-only actions run without a prompt and
are logged.

| AC | Status |
|----|--------|
| Tier 1 proposals execute automatically | ✅ |
| Every Tier 1 action written to audit log | ✅ |

**Phase:** [Phase 3](PHASES.md#phase-3--tiered-permission-engine)

---

### D2 — Tier 2 confirmation + biometric

**Story:** As a user, I approve a one-time maintenance action after reviewing
the literal command string and model reasoning, with a fresh biometric check.

| AC | Status |
|----|--------|
| Confirmation dialog shows exact command (not paraphrase) | ✅ |
| Biometric required before execution | ✅ |
| Single-use sudoers grant via `stackward-onetimer` | ✅ (requires host helper) |

---

### D3 — Tier 3 draft-only

**Story:** As a user, boundary-change proposals appear as drafts I apply
manually — never auto-executed.

| AC | Status |
|----|--------|
| Tier 3 blocked from automated path | ✅ |
| Draft diff shown in UI | ✅ |

---

### D4 — Proxmox power actions as Tier 2

**Story:** As a user, VM/LXC start/stop/restart requires Tier 2 confirmation.

| AC | Status |
|----|--------|
| Power actions classified Tier 2 | ✅ |
| Config/allocation actions blocked (Tier 3) | ✅ |

---

### D5 — Capability pack gates proposals

**Story:** As a user, I choose Monitor / Maintain / Provision in Settings;
the model may only propose actions allowed by my pack. Human gates unchanged.

| Pack | Allows |
|------|--------|
| **Monitor** (default) | Tier 1 reads + digests |
| **Maintain** | Monitor + Tier 2 one-timers |
| **Provision** | Off in v1; future broader proposals, still human-gated |

| AC | Status |
|----|--------|
| Settings selector for capability pack | ❌ |
| PermissionEngine rejects out-of-pack proposals | ❌ |

**PRD decision:** [§10 Capability packs](../PRD.md#10-decisions-resolved)

---

## Epic E — Security & recovery

### E1 — Panic revoke (in-app)

**Story:** As a user with my phone, I revoke all agent keys on the server and
wipe local credentials in one emergency action.

| AC | Status |
|----|--------|
| Settings panic button with biometric gate | ✅ |
| Server: `stackward-panic-revoke` helper | ✅ (requires bootstrap script) |
| Local credentials and profiles wiped | ✅ |
| Audit log exported on panic | ✅ |

**Phase:** [Phase 5](PHASES.md#phase-5--hardening)

---

### E2 — Lost-phone admin revoke

**Story:** As a user whose phone is lost, I revoke agent access via a separate
admin path without the app.

| AC | Status |
|----|--------|
| Documented admin procedure (SSH as admin → `stackward-panic-revoke` or manual `authorized_keys` edit) | ⚠️ (scripts exist; doc in [INSTALL.md](INSTALL.md) TBD) |
| In-app revoke useless without phone (expected) | ✅ |

---

### E3 — Key rotation

**Story:** As a user, I rotate the device SSH key without re-onboarding.

| AC | Status |
|----|--------|
| Push new key, verify, revoke old marker | ✅ |
| Audit entry for rotation | ✅ |

---

### E4 — Audit log export

**Story:** As a user, I export the full audit trail as JSON.

| AC | Status |
|----|--------|
| Export from Settings | ✅ |

---

### E5 — Tier 1 rule review reminder

**Story:** As a user, I am reminded every 30 days to review sudoers Tier 1 rules.

| AC | Status |
|----|--------|
| Reminder in Settings when overdue | ✅ |
| Sync rules from server sudoers snapshot | ✅ |

---

### E6 — Host key change recovery

**Story:** As a user, when a host key changes, I see a clear alert and can
re-pin after verification — not a silent connect failure.

| AC | Status |
|----|--------|
| TOFU rejects changed keys | ✅ |
| User-facing alert + re-pin flow | ❌ |

---

## Epic F — Settings & policy

### F1 — Capability pack selector

See [D5](#d5--capability-pack-gates-proposals).

---

### F2 — Elevated-identity acknowledgment in audit

**Story:** As a user, my choice to use an elevated SSH identity is recorded.

| AC | Status |
|----|--------|
| Audit entry at onboarding when elevated ack given | ❌ |

---

### F3 — Docker log ACL default; docker-group opt-in

**Story:** As a user, Docker logs are read via file ACL by default; joining the
`docker` group requires explicit opt-in with a root-equivalent warning.

| AC | Status |
|----|--------|
| Default: log-file ACL via `bootstrap_linux.sh` | ✅ (script) |
| In-app docker-group opt-in toggle + warning | ❌ |

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

Legacy `gemma-agent` references have been removed; use the canonical names above.

---

## Story ↔ phase map

| Epic | Primary phases |
|------|----------------|
| A — Onboarding | 0, 1 |
| B — Digests | 4, 5 |
| C — AI investigation | 2, 4 |
| D — Permissions | 3 |
| E — Security | 1, 5 |
| F — Policy | 3, 5 (+ new Settings work) |

---

## Dogfood exit checklist

Minimum stories that must pass on **Pixel 8 + live Linux/Proxmox/Docker host**
before calling v1 production-ready:

- [ ] **A2** — Full onboarding: password → key install → verify → password wiped
- [ ] **A3** — Elevated-account warning path tested once
- [ ] **A4** — Jump-host path tested (if used in your infra)
- [ ] **A5** — Proxmox token import + API digest (if Proxmox)
- [ ] **B1/B2** — Hourly digest with heuristic flags (not raw-only)
- [ ] **C1/C2** — Gemma model imported; summarization works on real logs
- [ ] **C3** — Natural-language question UI
- [ ] **D2** — Tier 2 service restart end-to-end with biometric
- [ ] **E1** — Panic revoke tested on a throwaway host
- [ ] **E2** — Admin revoke procedure documented and exercised once
- [ ] **E3** — Key rotation on a live host
- [ ] **D5/F1** — Capability pack selector (Monitor vs Maintain)

**Stretch (v1.1):** B3 push notifications, C4 container health, E6 host-key UI,
multi-host profiles.

---

## Future epic (out of v1 scope)

**G — Multi-host profiles:** Repository supports a list; UI currently uses
`firstOrNull()` only. Defer until single-host dogfood loop is solid.
