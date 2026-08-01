# Stackward — System Architecture

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
                    │         ├── SSH  (gemma-agent user)         │
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
User enters IP:port + one-time admin credential
         │
         ▼
App shows bootstrap script (bootstrap_linux.sh / bootstrap_proxmox.sh)
         │
         ▼  user reviews & approves
App runs script over SSH with admin credential
         │
         ├── creates gemma-agent user (locked password)
         ├── pushes restricted public key to authorized_keys
         ├── adds journal + Docker log ACLs
         ├── creates empty sudoers.d/gemma-agent stub
         └── (if Proxmox) creates scoped API token + role
         │
         ▼
App verifies connection with new Keystore key
         │
         ▼
Admin credential discarded (never stored)
```

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
3. Tier 3 (boundary changes) is unreachable from the agent's action space.
4. Docker group membership is opt-in with explicit warning (root-equivalent).
5. Host key change on any hop triggers alert before reconnecting.
