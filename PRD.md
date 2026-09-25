# PRD: Stackward — Personal Infrastructure Triage & Gated Improvements

## 1. Summary

An Android app for solo infrastructure operators to monitor and improve their
own systems — Linux hosts, Proxmox, Docker — from a phone without a laptop or
SaaS exposure.

**The job:** "Is something wrong right now? Should I open the laptop?"

**How it works:**
1. User connects phone to a host via SSH (one-time setup)
2. Phone queries a lightweight check script (`~/.stackward/check.sh`) for issues
3. Script returns **structured anomalies** (disk full, service down, security risk, etc.)
4. Phone gates improvements based on risk: safe actions auto-approve, risky require
   biometric confirmation, scary operations forbidden
5. Optional: Gemma summarizes issues in natural language (on-device, user-imported)

**Core constraint:** No autonomous actions, no cloud, nothing leaves the device except over the user's own SSH.

**Product invariants** (always true):

1. All data stays on the device and user-owned hosts — never cloud.
2. Risky/scary improvements are human-gated (biometric where elevation is involved).
3. Gemma is optional; without it, the app works as a structured anomaly detector.

## 2. Goals

- **One-time setup:** User creates a low-privilege host account, connects once
  with SSH password; app installs a key and the `check.sh` script, then wipes
  the password — never stored.
- **Detect anomalies:** App queries the check script regularly; script reads logs
  (systemd, Docker, Proxmox), runs heuristics (disk %, memory, error patterns),
  and returns structured issues + suggested improvements in JSON.
- **Summarize with Gemma (optional):** If user imports a quantized Gemma 2B/4B,
  phone summarizes the anomalies in natural language on-device. Without Gemma,
  the app still shows the structured anomalies.
- **Gate improvements by risk:**
  - **Safe** (restart a service, check logs): auto-approve, logged
  - **Risky** (disable SSH password auth, update packages): require biometric confirmation
  - **Scary** (edit sudoers, change Proxmox permissions): forbidden, documented workaround for manual operator action
- **Support jump hosts:** Monitor systems behind a bastion without treating the
  bastion as a monitored server.
- **Keep it private:** No prompts, logs, or host state leave the device except
  over SSH/API to the user's own infrastructure; no third-party SaaS, no cloud
  fallback, no team/multi-user (single operator per deployment).

## 3. Non-Goals (v1 and beyond)

- **No autonomous unattended actions** — every risky action requires a biometric
  gate from the phone's operator.
- **No team/multi-operator mode** — Stackward is for solo operators managing
  their own infrastructure; no team accounts, no shared credential pools.
- **No cloud or SaaS fallback** — if Gemma can't run on the phone, the app
  degrades to structured anomalies without summarization; never sends data
  to a cloud API.
- **No broad privilege grants by default** — the app recommends a dedicated
  low-privilege `stackward-agent` user; if the operator chooses root/sudo,
  the app warns and requires explicit acknowledgment before proceeding.
- **No broad actuation** — creating VMs, editing sudoers, granting Proxmox
  permissions, and other "scary" operations are out of scope. The app shows
  what should be done; an operator performs those steps manually at the
  console or via a documented admin script.
- **No Play Store enrollment in v1** — distribution is ADB + APK download;
  verification happens locally on the device, not via Play ecosystem.

## 4. Users

**Primary:** Solo homelab operators (one person, their own Proxmox/Docker
cluster, 10–1000 nodes) who want a fast triage path from their phone without
a laptop and without exposing logs to a third-party SaaS.

**Secondary:** None for v1. No small-business multi-operator story. No team
delegation. README and LICENSE (MIT) clarify this is a personal tool, not
ready for external contribution or redistribution.

## 5. Core Concepts

### 5.0 The Check Script Architecture

**`~/.stackward/check.sh`** is a shell script installed on each monitored host:

1. **Detects:** Reads logs, runs heuristics (disk, memory, errors, security risks)
2. **Reports:** Returns JSON with issues and suggested improvements
3. **Phone gates:** Phone queries the script, displays results, and decides whether to apply suggestions

**JSON response example:**
```json
{
  "issues": [
    {"type": "security", "severity": "critical", "message": "SSH password auth enabled"},
    {"type": "disk", "severity": "high", "message": "/var at 92%"}
  ],
  "suggestions": [
    {"id": "ssh-disable-pwd", "risk": "risky", "action": "disable_ssh_password"},
    {"id": "disk-alert", "risk": "safe", "action": "log_alert"}
  ]
}
```

### 5.1 Check Script Detection

`~/.stackward/check.sh` monitors:
- **Logs:** Parse systemd journal, Docker logs, Proxmox task logs for errors, warnings, crashes
- **Disk & Memory:** Read `/proc/mounts`, `df`, `/proc/meminfo`; flag >90% disk, low free memory
- **Service Health:** `systemctl list-units --failed`; count service restarts, status changes
- **Security:** Check `/etc/ssh/sshd_config` for password auth, use `ss` to list open ports

Returns JSON: `{timestamp, hostname, issues: [...], suggestions: [...]}`

### 5.2 Risk-Based Action Gating

| Risk Level | Description | Examples | Phone Action |
|------------|-------------|----------|--------------|
| **Safe** | No elevation, read-only, zero side effects | tail logs, check service status, read config | Auto-approve, logged |
| **Risky** | Requires elevation or harder to undo | restart service, reboot host, disable SSH password auth, update packages | Require biometric confirmation + show literal command before applying |
| **Scary** | Changes system boundaries, out of scope | edit sudoers, modify Proxmox permissions, change SSH port | Forbidden; app shows manual workaround with copy-paste command |

### 5.2 Identity, Credentials, and Setup

**One-time setup flow:**
1. User creates a low-privilege host account: `sudo adduser stackward-agent`
2. User runs: `ssh stackward-agent@host "bash < <(curl .../install.sh)"`
3. User enters password once; script installs SSH key and `check.sh`, then wipes password
4. Password never stored on phone; all future SSH is key-based

**Key security practices:**
- SSH keypair lives in Android Keystore (hardware-backed where available),
  non-exportable, `setUserAuthenticationRequired(true)` — every SSH sign
  requires a fresh biometric prompt.
- Recommended identity: dedicated low-privilege `stackward-agent` user
  (no elevation, no sudo needed for read-only checks).
- User choice: operator may connect with a more privileged account (root, sudo).
  The app detects elevation during login, warns with clear risk language,
  and requires explicit acknowledgment before key install.
- Biometric data never leaves the device or is sent server-side; biometric gates
  only local key operations.
- Proxmox and Docker: optional, configured via `~/.stackward/env` on the host
  if the user wants to include those datasources in anomaly detection.

### 5.3 Connectivity & Polling

- **Direct SSH:** Phone connects directly to hosts reachable from the internet.
- **Jump-host support:** For LAN-only infra, phone → bastion → internal host
  (ProxyJump via SSH channel forwarding). Bastion is not registered as a
  monitored server, only as a relay.
- **Host key pinning (TOFU):** Each host's key is pinned on first connect;
  changes alert the user.
- **Scheduled checks:** Phone can poll the check script on a user-defined
  cadence (e.g., every 4 hours, every night); reconnect-with-backoff on
  temporary failures.

## 6. Key User Stories

Outcome-focused stories for v1 dogfood acceptance.

1. **First connect** — I create a `stackward-agent` user on my host, run the
   install script once with the password, and the app installs the key and
   wipes the password. If I choose a more privileged account (root/sudo), the
   app warns me and I must acknowledge the risk.
2. **Dashboard glance** — I open the app; it shows all my configured hosts at
   a glance (Proxmox, Docker, bastion). Green = no issues, red = problems.
   Tap any host to see what's wrong.
3. **Quick check** — I tap a host; app queries `check.sh` and shows issues +
   suggestions in under 10 seconds. Disk at 92%, PostgreSQL failed, SSH
   password auth still on.
4. **Natural-language summary (optional)** — If I've imported a Gemma model,
   the app summarizes the anomalies in plain English. If I haven't, I still see
   the structured list.
5. **Safe action** — I see "Check logs" suggestion; I tap it; app runs immediately
   (no confirmation needed) and shows the tail of the journal.
6. **Risky action with biometric** — I see "Restart nginx" suggestion. I tap "apply,"
   confirm with my fingerprint, and it restarts. App shows the literal command
   before executing.
7. **Scary action blocked** — The app suggests "Disable SSH password auth" but
   shows "Manual step required" with the exact command to run at the console.
   I copy-paste it locally.
8. **Scheduled monitoring** — For my Proxmox host, I enable "auto-check every 4h."
   For my backup server, I leave it manual-only. Notifications alert me only if
   something is critical (disk >95%, service down).
9. **Monitor through jump host** — I onboard my internal Proxmox cluster via a
   bastion; the bastion is not registered as a monitored server, just a relay.

## 7. Success Metrics

- **Install-to-first-check:** User runs install script → app connects →
  shows first anomalies in under 5 minutes (no model import needed).
- **Model optional:** Without Gemma, the app still provides value as a
  structured anomaly detector; with Gemma, summaries arrive within 30s
  on a 2026 flagship.
- **Biometric integrity:** Zero credential data or biometric data leaves the
  device in network audit.
- **False-positive rate:** Heuristic anomaly detection flags fewer than N false
  positives per 100 real issues (tracked during dogfooding; target: 10–20%).
- **Dogfood:** Author uses the app weekly to triage their own cluster; would
  open it instead of a laptop for "what's wrong?" questions.

## 8. Risks & Mitigations

| Risk | Mitigation |
|---|---|
| Check script is compromised or returns malicious suggestions | Phone shows literal command before applying; user confirms via biometric; audited locally |
| Compromised jump host MITMs inner connections | Per-hop SSH host key pinning; TOFU validation and alerting on key change |
| Heuristics flag false positives (e.g., false OOM alert) | Human review before action; "risky" actions still require biometric gate |
| One-time agent password used during setup | Held in Keystore-encrypted memory only; wiped immediately after `authorized_keys` install |
| User connects with root/passwordless-sudo | Login probe detects elevation; setup workflow warns and requires explicit acknowledgment |
| Lost/stolen phone with unlocked session | Every key operation (SSH, apply action) re-triggers biometric — not app unlock alone |
| No Gemma model available | App degrades to structured anomalies; summarization is optional, not required for value |

## 9. Phased Roadmap

See [docs/PHASES.md](docs/PHASES.md) for full detail. Focus is on Monitor tier only in v1.

- **Phase 0/1** — Setup (one-time password → authorized_keys, key install in
  Keystore, `check.sh` deployment via bootstrap script, jump-host support)
- **Phase 2** — Check script + heuristics (detect errors, OOM, disk full, security risks)
- **Phase 3** — Risk-based action gating (safe auto-approve, risky require
  biometric, scary forbidden)
- **Phase 4** — Gemma optional summarization (user imports model, phone runs
  inference on-device, no model = structured anomalies still work)
- **Phase 5** — Hardening & audit (local audit log, key rotation, panic revoke,
  biometric-on-every-action pattern)

## 10. Decisions (resolved)

| Topic | Decision |
|-------|----------|
| Core agent | Shell script (`check.sh`), not a daemon; phone queries on-demand or on schedule |
| Recommended identity | `stackward-agent` (dedicated low-privilege Linux user, no elevation) |
| Risk-based gating | Safe actions auto-approve; risky actions require biometric; scary actions forbidden |
| Gemma integration | Optional; phone imports user's model file; without it, structured anomalies still work |
| Scary operations (sudoers, Proxmox perms, SSH port changes) | Out of scope; documented manual workarounds provided |
| Jump host role | Pure relay — not registered as a monitored server; only used for ProxyJump auth |
| v1 scope | Monitor tier only (read-only detection + risk-gated improvements); no Maintain or Provision phases |
| Distribution (v1) | ADB + downloaded APK; no Play Store enrollment; ~20-device limit due to Android developer verification |
