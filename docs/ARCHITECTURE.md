# Stackward — System Architecture

> ⚠️ **Superseded by the Lookout pivot.** This describes the earlier
> broad-actuation architecture (Gemma emitting individual Tier 1/2/3 shell
> proposals over a persistent SSH/Proxmox-API connection). The current
> direction (`STRATEGY.md`, `PRD.md`) instead has the phone query a single
> `~/.stackward/check.sh` script for structured anomalies, with
> safe/risky/scary risk-based gating on its suggestions. Much of the code
> described below (`PermissionEngine`, `ProxmoxCommands`, `AgentKeyManager`,
> host-key pinning) is real and still applies at the connection/credential
> layer — it's the "model proposes individual commands" framing in Layers
> 1–2 below that's been replaced. Don't build new work against this file
> without checking it against `STRATEGY.md` first.

## Overview

Stackward is an Android app that runs a quantized Gemma 4 model on-device and
connects to the user's own infrastructure over SSH and (optionally) the
Proxmox REST API. The model **proposes** actions; a permission engine on the
phone **decides** whether each proposal may execute.

```
┌─────────────────────────────────────────────────────────────────┐
│                        Android Device                           │
│  ┌──────────────┐   ┌─────────────────┐   ┌────────────────┐ │
│  │  Gemma 4     │──▶│ Permission      │──▶│ Connection     │ │
│  │  (on-device) │   │ Engine (T1/T2/3)│   │ Layer (SSH/API)│ │
│  └──────────────┘   └────────┬────────┘   └───────┬────────┘ │
│                              │                     │           │
│  ┌──────────────┐            │ biometric           │           │
│  │ Android      │◀───────────┘ (Tier 2+)           │           │
│  │ Keystore     │                                  │           │
│  └──────────────┘                                  │           │
└────────────────────────────────────────────────────┼───────────┘
                                                     │
                    ┌────────────────────────────────┼────────────┐
                    │  Internet / user's network     │            │
                    │                                ▼            │
                    │  ┌──────────────┐    ┌──────────────────┐  │
                    │  │ Jump host /  │───▶│ Internal targets │  │
                    │  │ Proxmox host │    │ (LAN hosts)      │  │
                    │  └──────┬───────┘    └──────────────────┘  │
                    │         │                                   │
                    │         ├── SSH  (stackward-agent user)     │
                    │         └── API  (Proxmox :8006, scoped tok)│
                    └─────────────────────────────────────────────┘
```

## Layers

### 1. Inference Layer (on-device)

- Gemma 4 E2B/E4B via MediaPipe LLM Inference API.
- Receives user queries and tool outputs; emits **structured proposals**
  (JSON / function-calling), never raw shell.
- Stateless between calls — rolling summaries supplied by the app for
  cross-session context.

### 2. Permission Engine

Central safety gate. Every model proposal passes through here before any
network call is made.

| Tier | Engine behaviour |
|------|-----------------|
| **Tier 1** | Allow if action matches a pre-vetted rule in `sudoers.d` or a read-only tool. Log and execute. |
| **Tier 2** | Block until user confirms exact command string + model reasoning. Biometric re-prompt. Write temporary single-use sudoers grant, execute, remove grant. |
| **Tier 3** | Reject from automated path. App may draft a suggested diff; human applies manually. |

The engine has two backends:

- **SSH / sudoers** — Linux host commands.
- **Proxmox API** — VM/LXC status and power actions via scoped token.

### 3. Connection Layer

- **SSHJ** for programmatic SSH (direct and jump-host via channel forwarding).
- **Local port-forward** for reaching Proxmox API (`:8006`) or internal Docker
  hosts through a jump host.
- Host key pinning (TOFU) per hop.
- Reconnect-with-backoff for scheduled digests.

### 4. Credential Store

- Ed25519 keypair in Android Keystore (`setUserAuthenticationRequired(true)`).
- Proxmox API token stored alongside, same biometric gate.
- No credentials persisted in plaintext outside Keystore.

## Tier 2 Data Flow (one-timer elevation)

Example: model proposes restarting `nginx`.

```
User: "restart nginx, it's been returning 502s"
         │
         ▼
┌─────────────────┐
│ Gemma 4         │  emits structured proposal:
│ (on-device)     │  { "tier": 2,
└────────┬────────┘    "action": "sudo",
         │             "command": "/usr/bin/systemctl restart nginx",
         │             "reason": "502 errors in access log since 14:32" }
         ▼
┌─────────────────┐
│ Permission      │  command NOT in sudoers.d → Tier 2 path
│ Engine          │
└────────┬────────┘
         ▼
┌─────────────────┐
│ Confirmation UI │  shows literal command string + reason
│                 │  user taps Approve
└────────┬────────┘
         ▼
┌─────────────────┐
│ Biometric       │  Android Keystore requires fresh auth
│ prompt          │
└────────┬────────┘
         ▼
┌─────────────────┐
│ Connection      │  1. write single-use sudoers.d rule
│ Layer           │  2. SSH: sudo /usr/bin/systemctl restart nginx
│                 │  3. delete sudoers.d rule
│                 │  4. return output to model
└────────┬────────┘
         ▼
┌─────────────────┐
│ Audit log       │  timestamp, tier, command, approval, output
└─────────────────┘
```

## Bootstrap Flow (Phase 0/1)

```
Info screen: recommended default — create restricted user
  (Debian: sudo adduser stackward-agent)
         │
         ▼
Connect as chosen SSH user with one-time password
  (default: stackward-agent; elevated accounts allowed with ack)
         │
         ▼
Login probe: whoami / groups / passwordless sudo?
  → if elevated: show risk warning, require explicit acknowledgment
         │
         ▼
Confirm authorized_keys install (ssh-copy-id style, home of that user)
         │
         ▼
App appends device public key to ~/.ssh/authorized_keys
         │
         ▼
App verifies connection with Keystore agent key
         │
         ▼
Password wiped from memory (never written to disk)
```

Proxmox `pveum` / privileged host helpers are **out-of-band admin scripts**
(`scripts/bootstrap_*.sh`) — run as a real admin on the host, not by the
app and not as a requirement for key-only onboarding.

Remote identity power and capability scope are **user policy**. Product
invariants remain: on-device inference and human-gated elevation.

## Log Reading (Phase 4 MVP)

Three uniform read-only sources, all Tier 1:

| Source | Access method | Pre-filter |
|--------|--------------|------------|
| systemd journal | `journalctl` via `systemd-journal` group | `--since`, `-p err` |
| Docker containers | read `/var/lib/docker/containers/*/*.log` via ACL | container name, tail |
| Proxmox | API: node status, task log | time window |

Output is truncated to fit model context before inference. Scheduled hourly
digest runs without confirmation; on-demand queries same path.

## Security Invariants

1. Model never gets raw shell access.
2. Biometric data never leaves the device.
3. Tier 3 (boundary changes) is unreachable from the automated action path.
4. Docker group membership is opt-in with explicit warning (root-equivalent).
5. Host key change on any hop triggers alert before reconnecting.
6. Elevated SSH identities (root / passwordless sudo) require explicit
   onboarding acknowledgment — never silent.
