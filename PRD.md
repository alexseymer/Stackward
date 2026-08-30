# PRD: Stackward — On-Device LLM for Server & Infra Monitoring

## 1. Summary

An Android app that runs a quantized Gemma 4 model (E2B/E4B) fully on-device
to help a user monitor and manage their own infrastructure — plain Linux
hosts, Proxmox clusters, and Docker containers — over SSH, without sending
credentials, logs, or command output to any third-party cloud service.

The core design constraint: **the model proposes, the human (or a narrow,
pre-approved rule) disposes.** No autonomous privilege escalation, no silent
broadening of what the agent may do, no biometric data leaving the device.

**Product invariants** (always true):

1. Inference stays on-device (local brain).
2. Mutating / elevating actions are human-gated (confirm + biometric where
   elevation is involved).

**User policy** (defaults are safe; the operator chooses):

- How powerful the remote SSH/API identity is (restricted agent recommended;
  elevated accounts allowed only with explicit acknowledgment).
- Which capabilities are in scope (read-only monitoring vs maintenance vs
  broader provisioning-style work), still gated by the invariants above.

## 2. Goals

- Let a user point the app at a server (IP/host + port) and connect with an
  identity *they* choose — recommended default: a dedicated least-privilege
  agent user with almost no manual server-side setup beyond creating the
  account.
- Read and summarize logs (systemd journal, Docker container logs, Proxmox
  task logs) using the on-device model — typically read-only, zero elevation.
- Allow scoped, confirmed command execution for routine and one-off
  maintenance tasks (service restarts, VM/LXC power actions); broader
  actions only when the user has enabled the corresponding capability and
  confirms each elevation as required.
- Support infra that isn't directly reachable from the internet, via SSH
  jump-host / tunnel patterns.
- Keep all inference local. No prompts, logs, or command output leave the
  device except over the user's own SSH/API connections to their own
  infrastructure.
- Make privilege and scope tradeoffs visible: safe defaults, never silent
  escalation.

## 3. Non-Goals (v1)

- No autonomous unattended write actions of any kind (Tier 2/3 always
  require explicit human confirmation).
- No multi-user / team accounts — single operator, single device (or
  device + backup) per deployment.
- No cloud fallback inference. If the phone can't run the model, the app
  degrades to "no AI summarization," not "send data to a cloud API."
- No forcing a single remote identity: the app recommends
  `stackward-agent`, but does not permanently forbid stronger accounts —
  it warns and requires acknowledgment instead.

Provisioning-style work (creating VMs/LXCs/hosts) is **out of the default
capability set** for v1, not a permanent ban. Users may opt into broader
scopes later; every mutating step remains human-gated.

## 4. Users

Solo operators / small teams who self-host — homelab users, small
businesses running Proxmox + Docker — who want a fast way to ask "what's
wrong right now" from their phone without opening a laptop or exposing
infra to a third-party SaaS monitoring tool.

## 5. Core Concepts

### 5.0 Decision layers

| Layer | Who decides |
|--------|-------------|
| Where intelligence runs | Product (on-device) |
| Whether an action may run | User (confirm / rules they approved) |
| How powerful the SSH/API identity is | User (setup choice; elevated = explicit ack) |
| What kinds of work are in scope | User (capabilities they enable) |

### 5.1 Tiered Permission Model

| Tier | Description | Examples | Confirmation |
|------|-------------|----------|---------------|
| **Tier 1 — Routine** | Read-only or pre-vetted low-risk actions | log reads, `systemctl status`, VM/LXC status via Proxmox API, container list | None (logged) |
| **Tier 2 — One-timer** | Named, bounded elevation for a single action | restart a service, restart/stop a container, power-cycle a VM | Explicit per-action confirmation + biometric |
| **Tier 3 — Boundary change** | Anything that changes what the agent is *allowed* to do | editing `sudoers.d`, granting new Proxmox API permissions, adding new Tier 1 rules | Out of the automated path; human-only, hard confirmation, drafted not auto-applied |

### 5.2 Identity & Credentials

- **Recommended default:** dedicated low-privilege OS user per host
  (`stackward-agent`), created out-of-band by an admin
  (`sudo adduser stackward-agent` on Debian). Password used once
  (ssh-copy-id style) to install the device public key into
  `~/.ssh/authorized_keys`, then wiped — never stored.
- **User choice:** the operator may connect with a different (including
  elevated / root / passwordless-sudo) account. The app detects elevation
  during the login probe, shows a clear risk warning, and requires
  explicit acknowledgment before key install. Optional host helpers
  (`scripts/bootstrap_linux.sh`) install narrow sudoers rules for
  Stackward helpers; they are admin-run, not required for basic key-only
  setup.
- SSH keypair generated in Android Keystore (hardware-backed where
  available), non-exportable, `setUserAuthenticationRequired(true)` —
  every signing operation requires a fresh biometric prompt.
- Proxmox: dedicated API token (`stackward-agent@pve`) bound to a custom role
  with explicit, minimal privileges (`VM.Audit`, `Sys.Audit`,
  `VM.PowerMgmt` — never `VM.Config.*` / `VM.Allocate` in the default role).
  Token creation is **out-of-band** (admin `pveum`); broader roles are a
  user/admin policy choice. (`VM.Monitor` was dropped in PVE 9; bootstrap
  falls back to a legacy privilege set that still includes it on older hosts.)
- Docker: log access via file/group ACL on Docker's log directory, **not**
  `docker` group membership (which is root-equivalent) unless the user
  explicitly opts in and is warned.
- No biometric data ever transmitted or stored server-side. Biometrics
  gate local key usage only.

### 5.3 Connectivity

- Direct SSH for hosts reachable from the internet.
- Jump-host / tunnel support (`ProxyJump` equivalent via channel
  forwarding) for LAN-only infra — e.g. phone → bastion/Proxmox host →
  internal target, or local port-forward for reaching the Proxmox API
  (`:8006`) or Docker hosts on the LAN.
- Host key pinning (TOFU) per hop, with alerting on change.
- Reconnect-with-backoff for scheduled/unattended log digests.

## 6. Key User Stories

Outcome-focused stories for dogfood acceptance. Detailed acceptance criteria
live in [docs/USER_STORIES.md](docs/USER_STORIES.md).

1. **First connect** — I create `stackward-agent` on my host, connect once
   with password, and the app installs its key and wipes the password. If I
   choose a more privileged account, the app warns me and I must acknowledge
   the risk before continuing.
2. **Stay informed** — I get periodic digests of problems across journal,
   Docker, and Proxmox without opening a laptop.
3. **Investigate** — I ask a natural-language question about a container or
   service and get a correlated on-device summary.
4. **Act safely** — I approve a one-time maintenance action after seeing the
   literal command, model reasoning, and a biometric check.
5. **Recover from loss** — I can revoke agent access from the phone *or* via
   a documented admin path if the phone is gone.
6. **Reach LAN hosts** — I onboard through a jump host without treating the
   bastion as a monitored server.
7. **Control scope** — I choose capability level (read-only / maintenance /
   future provisioning) in Settings; mutations stay human-gated.
8. **Audit & rotate** — I export audit history, rotate keys, and review Tier 1
   rules on a schedule.

## 7. Success Metrics

- Time from "enter IP" to "first working log digest" (target: < 5 min).
- Zero standing broad-privilege grants created without explicit user /
  Tier 3 human action.
- Zero biometric or credential data observed leaving the device in network
  audit.
- False-positive rate of anomaly flags in digests (tracked qualitatively
  in early dogfooding).

## 8. Risks & Mitigations

| Risk | Mitigation |
|---|---|
| Small on-device model hallucinates a plausible but wrong/destructive command | All mutating actions gated by Tier 2/3 confirmation showing literal command string, not paraphrase |
| Compromised jump host MITMs inner hop | Per-hop host key pinning |
| Docker group membership grants root-equivalent access | Default to log-file ACL access; require explicit opt-in + warning for group membership |
| One-time agent password used during setup | Keystore-encrypted in memory only; wiped after authorized_keys install; never written to disk |
| User connects with root / broad sudo | Login probe detects elevation; setup blocked until explicit acknowledgment; audit notes elevated identity |
| Lost/stolen unlocked phone | Every sensitive key operation re-triggers biometric prompt, not just app unlock |

## 9. Phased Roadmap

See [docs/PHASES.md](docs/PHASES.md) for full detail.

- **Phase 0/1** — Agent setup (prep info, one-time password →
  authorized_keys, jump-host support; Proxmox token out-of-band; elevated
  identity requires acknowledgment)
- **Phase 2** — Local model integration (Gemma 4 E2B/E4B via MediaPipe LLM
  Inference API)
- **Phase 3** — Tiered permission engine (sudoers + Proxmox role backend)
- **Phase 4** — MVP feature: unified log reading (journal + Docker +
  Proxmox), read-only
- **Phase 5** — Hardening: audit log, key rotation, panic revoke

## 10. Decisions (resolved)

| Topic | Decision |
|-------|----------|
| Canonical agent identity | `stackward-agent` (Linux SSH user); `stackward-agent@pve` (Proxmox API user) |
| Host type detection | Auto-detect after connect; optional override in Settings later |
| Tier 3 agent proposals | Draft-only for human review — never auto-applied |
| Jump host role | Pure relay — bastion gets the agent key for ProxyJump auth only; not registered as a monitored ServerProfile |
| Capability packs (Settings) | Three packs gate what the model may *propose*; Tier 2/3 human gates unchanged: **Monitor** (Tier 1 reads + digests), **Maintain** (+ Tier 2 one-timers), **Provision** (future, off by default in v1) |
