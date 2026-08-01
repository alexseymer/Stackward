# PRD: Gemma Agent — On-Device LLM for Server & Infra Monitoring

## 1. Summary

An Android app that runs a quantized Gemma 4 model (E2B/E4B) fully on-device
to help a user monitor and manage their own infrastructure — plain Linux
hosts, Proxmox clusters, and Docker containers — over SSH, without sending
credentials, logs, or command output to any third-party cloud service.

The core design constraint: **the model proposes, the human (or a narrow,
pre-approved rule) disposes.** No autonomous privilege escalation, no
standing broad sudo, no biometric data leaving the device.

## 2. Goals

- Let a user point the app at a server (IP/host + port) and have it
  provision a safe, least-privilege agent identity with almost no manual
  server-side setup.
- Read and summarize logs (systemd journal, Docker container logs, Proxmox
  task logs) using the on-device model — read-only, zero elevation.
- Allow scoped, confirmed command execution for routine and one-off
  maintenance tasks (service restarts, VM/LXC power actions).
- Support infra that isn't directly reachable from the internet, via SSH
  jump-host / tunnel patterns.
- Keep all inference local. No prompts, logs, or command output leave the
  device except over the user's own SSH/API connections to their own
  infrastructure.

## 3. Non-Goals (v1)

- No autonomous unattended write actions of any kind (Tier 2/3 always
  require explicit human confirmation).
- No multi-user / team accounts — single operator, single device (or
  device + backup) per deployment.
- No provisioning of *new* infrastructure (creating VMs, LXCs, or hosts).
  The agent manages what already exists.
- No cloud fallback inference. If the phone can't run the model, the app
  degrades to "no AI summarization," not "send data to a cloud API."

## 4. Users

Solo operators / small teams who self-host — homelab users, small
businesses running Proxmox + Docker — who want a fast way to ask "what's
wrong right now" from their phone without opening a laptop or exposing
infra to a third-party SaaS monitoring tool.

## 5. Core Concepts

### 5.1 Tiered Permission Model

| Tier | Description | Examples | Confirmation |
|------|-------------|----------|---------------|
| **Tier 1 — Routine** | Read-only or pre-vetted low-risk actions | log reads, `systemctl status`, VM/LXC status via Proxmox API, container list | None (logged) |
| **Tier 2 — One-timer** | Named, bounded elevation for a single action | restart a service, restart/stop a container, power-cycle a VM | Explicit per-action confirmation + biometric |
| **Tier 3 — Boundary change** | Anything that changes what the agent is *allowed* to do | editing `sudoers.d`, granting new Proxmox API permissions, adding new Tier 1 rules | Out of the agent's action space; human-only, hard confirmation, drafted not auto-applied |

### 5.2 Identity & Credentials

- Dedicated low-privilege OS user per host (`gemma-agent`), password
  locked, SSH-key-only.
- SSH keypair generated in Android Keystore (hardware-backed where
  available), non-exportable, `setUserAuthenticationRequired(true)` —
  every signing operation requires a fresh biometric prompt.
- Proxmox: dedicated API token (`gemma-agent@pve`) bound to a custom role
  with explicit, minimal privileges (`VM.Monitor`, `VM.Audit`,
  `VM.PowerMgmt` — never `VM.Config.*` / `VM.Allocate` in v1).
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

1. As a user, I enter an IP/hostname, port, and a one-time admin
   credential, and the app provisions everything else (user, key, sudoers
   skeleton, Proxmox token) automatically.
2. As a user, I get an hourly digest of anomalies across journal, Docker,
   and Proxmox logs without doing anything.
3. As a user, I ask "why is container X unhealthy" and get a summarized,
   correlated answer pulled from its logs.
4. As a user, I approve a one-time restart of a service after reviewing
   the exact command and the model's reasoning, gated by a fresh biometric
   check.
5. As a user, if my phone is lost, I can revoke the agent's access with
   one action (using a separately retained admin path).

## 7. Success Metrics

- Time from "enter IP" to "first working log digest" (target: < 5 min).
- Zero standing broad-privilege grants created without explicit Tier 3
  human action.
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
| Bootstrap step requires a real admin credential | Credential used once, never persisted, bootstrap script shown to user before execution |
| Lost/stolen unlocked phone | Every sensitive key operation re-triggers biometric prompt, not just app unlock |

## 9. Phased Roadmap

See [docs/PHASES.md](docs/PHASES.md) for full detail.

- **Phase 0/1** — Bootstrap & provisioning (SSH user, key, Proxmox token,
  jump-host support)
- **Phase 2** — Local model integration (Gemma 4 E2B/E4B via MediaPipe LLM
  Inference API)
- **Phase 3** — Tiered permission engine (sudoers + Proxmox role backend)
- **Phase 4** — MVP feature: unified log reading (journal + Docker +
  Proxmox), read-only
- **Phase 5** — Hardening: audit log, key rotation, panic revoke

## 10. Open Decisions

- Auto-detect host type (plain/Proxmox/Docker) during onboarding vs. user
  declares it upfront.
- Whether Tier 3 changes should be *draftable* by the agent (for human
  review) or entirely outside its action space from day one.
- Whether jump hosts get their own full agent identity (monitored target
  too) or act as pure relays.
