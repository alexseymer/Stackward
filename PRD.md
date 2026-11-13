# PRD: Stackward — A Read-Only Health Check for Your Own Linux Hosts

**Revised 2026-11-12 — scope narrowed (decisions D5–D8, §10).** v1 is a read-only
health check. It no longer runs actions on hosts, no longer includes an on-device
model, no longer ships the older log/Proxmox features, and covers Linux hosts only.
Earlier revisions (2026-10-23, decisions D1–D4) are kept in §10. Where this spec
asks for something the code doesn't do yet, §11 lists the gap; treat §11 as the
authoritative "spec is ahead of code" list.

## 1. Summary

**One sentence:** Stackward tells a solo operator, from their phone and within
10 seconds of a fingerprint, whether any of their Linux hosts has a problem right
now — and changes nothing on those hosts.

**The job:** "Is something wrong right now? Should I open the laptop?"

**How it works:**
1. One-time setup: the app connects to a host over SSH, installs its own key
   and the check script `~/.stackward/check.sh`, then forgets the setup secret.
2. When the user opens a host and confirms with biometric, the phone runs
   `check.sh` over SSH.
3. The script returns **structured issues** as JSON (disk full, failed service,
   risky SSH config, …), each optionally with a line of manual advice.
4. The phone shows the issues, worst first. Advice is text the operator can read
   and copy; the app never runs it.

**Product invariants** (always true):

1. **Nothing leaves the device** except over SSH to the user's own
   infrastructure. No cloud, no SaaS, no telemetry, no model downloads.
2. **No background access.** Every SSH session to a host happens while the
   user is in the app and has confirmed with biometric. Nothing connects to a
   host on a schedule or while the phone is locked (D1).
3. **Read-only.** The app changes nothing on a monitored host except its own
   footprint: its public key in `authorized_keys` and `~/.stackward/check.sh`.
   It runs no remediation, restarts nothing, and never executes text that came
   from the host (D5).
4. **No elevated privileges.** The agent user needs no `sudo`. Everything the
   check reads is readable by an ordinary account (plus, optionally, membership of
   the `systemd-journal` group and a read ACL on Docker logs).

## 2. Goals

- **One-time setup:** connect once with a password or private key; the app
  installs its public key and `check.sh`, verifies key login, and wipes the
  setup secret — never stored.
- **On-demand checks:** run `check.sh` on a host when the user asks; show
  structured issues in under 10 seconds.
- **Trustworthy results:** never show "all clear" for something the check could
  not read; an unreachable or stale host is shown as such, not as healthy.
- **Reach LAN-only hosts:** jump host (bastion as relay, not as a monitored
  server) and optional port knocking.
- **Recover from loss:** panic revoke on the phone, and a documented admin
  procedure on the host.

## 3. Non-Goals (v1)

- **No actions on hosts.** No restart, cleanup, update or reboot, and no "apply"
  button. Advice is text only (D5).
- **No on-device AI model.** No summaries, no model download or import (D7).
- **No log analysis on the phone.** `check.sh` runs on the host; the phone shows
  what it reports.
- **No Proxmox- or Docker-specific monitoring.** Proxmox and Docker hosts are
  checked as plain Linux hosts (D8). Proxmox API access and VM/LXC views are out.
- **No background monitoring or push alerts** (D1).
- **No team/multi-operator mode,** no accounts, no cloud or server fallback.
- **No iOS.** Android only. No Play Store in v1; distribution is a signed dogfood
  APK from GitHub Releases, installed with `adb install -r`.
- **No broad privilege.** Connecting as root or a sudo-capable user is allowed,
  but onboarding detects it, warns, and requires explicit acknowledgment
  (recorded in the audit log).

## 4. Users

**Primary:** a solo homelab operator — one person, their own Linux hosts
(typically a handful to a few dozen) — who wants a fast "is anything wrong?"
answer from a phone without exposing logs to a third party.

**Secondary:** none. No small-business or team story. A personal MIT-licensed
tool, not ready for outside contribution.

## 5. Core Concepts

### 5.1 The check script (`~/.stackward/check.sh`)

A dependency-free bash script on each monitored host. It runs unprivileged as
the SSH user, reads system state, and prints one JSON document. It never
changes anything.

**Output contract** (parsed by `CheckScriptParser`; target shape, see §11 R2):

```json
{
  "timestamp": "2026-11-12T09:14:02Z",
  "hostname": "web-01",
  "coverage": {"journal": "ok", "docker": "absent", "systemd": "ok"},
  "issues": [
    {"type": "disk", "severity": "critical", "message": "/var at 96% capacity",
     "advice": "Free space under /var, e.g. journalctl --vacuum-time=7d"},
    {"type": "security", "severity": "critical", "message": "SSH password authentication enabled",
     "advice": "Set PasswordAuthentication no in sshd_config (at the console, not over this connection)"}
  ]
}
```

- `severity` ∈ `critical | high | medium`.
- `advice` is optional text, shown to the operator and never executed.
- `coverage` states what the script could and could not read (`ok`, `denied`,
  `absent`), so "no issues" is never claimed for a check that didn't run.
- Older copies that still send `suggestions[]` are parsed and the field ignored.

**What it detects** (defaults; each threshold can be overridden through an
environment variable of the same name):

| Area | Check | Severity |
|---|---|---|
| Disk | any real mounted filesystem > `DISK_WARNING_PCT` (85) | high |
| Disk | any real mounted filesystem > `DISK_CRITICAL_PCT` (95) | critical |
| Memory | used > `MEM_WARNING_PCT` (80) | high |
| Services | each failed systemd unit | critical |
| Security | `PasswordAuthentication yes` in `sshd_config` | critical |
| Security | more than 20 listening TCP ports | medium |
| Logs | more than 5 `err`-priority entries in the last `SYSTEMD_JOURNAL_LINES` (100) journal lines | medium |

"Real" excludes pseudo and read-only filesystems such as snap loop mounts.
Docker (exited containers) is reported on a best-effort basis when readable; it is
not a v1 requirement (D8). Candidate checks for later: swap, firewall state,
service restart counts, pending security updates, time sync, certificate expiry,
backup age.

**Delivery:** the app installs `check.sh` during onboarding as the SSH user (no
root needed) and replaces it when the app's bundled version is newer (D2). The
optional admin script `scripts/bootstrap_linux.sh` only grants extra *read*
access (journal group, Docker log ACL) and installs the credential-lifecycle
helpers (§5.3).

### 5.2 Read-only guarantee

Nothing in the app can change a host's state beyond its own footprint:

- The only commands the phone sends are: `bash ~/.stackward/check.sh`; the
  onboarding steps that install the public key and `check.sh`; the login probe;
  and key rotation/revocation.
- There is no catalog of runnable actions, no "apply" path, no model, and no
  sudoers helper for remediation. Text from the host (messages, advice, unit
  names) is data for display only and is never executed or interpreted.
- A compromised or buggy `check.sh` can at worst show misleading text or hide a
  problem. It cannot make the phone run anything, because the phone has no code
  path that executes host-supplied content.

### 5.3 Identity, credentials, and setup

**Onboarding (in the app):**
1. The operator creates an SSH account on the host — recommended:
   `sudo adduser stackward-agent` (low privilege, no sudo).
2. In the app: host, port, username, and a one-time password or private key;
   optionally a jump host and a port-knock sequence.
3. The app connects, pins the host key (trust on first use), detects whether
   the account is elevated (root or passwordless sudo) and, if so, requires an
   explicit acknowledgment.
4. The app installs its public key into `~/.ssh/authorized_keys`, installs
   `check.sh` (D2), verifies key-based login, and wipes the password or private
   key from memory. Nothing from step 2 is stored.
5. Optional: run `scripts/bootstrap_linux.sh` as root to grant journal/Docker log
   read access and to install the revoke helpers.

**Keys and biometric:**
- The device SSH key lives in Android Keystore (hardware-backed where
  available), cannot be exported, and requires biometric for every use. Each
  SSH session therefore requires one biometric confirmation, which is what
  enforces invariant 2.
- Biometric data never leaves the device; it only unlocks local key use.
- Panic revoke removes the key from hosts it can still reach and wipes all
  local credentials, pins and results. Key rotation is supported.

### 5.4 Connectivity

- **Direct SSH** to reachable hosts.
- **Jump host:** phone → bastion → internal host via SSH channel forwarding.
  The bastion is a relay only and is not a monitored host. Each hop's key is
  pinned separately.
- **Port knocking** (optional) before connecting.
- **Host key pinning:** a changed key is refused and surfaced to the user;
  re-pinning is a deliberate user action.
- **Retries:** failed connections retry with backoff while the check is in
  progress. There is no background retry or schedule.

## 6. Key User Stories (v1 acceptance)

1. **First connect** — I create `stackward-agent`, enter the host and the
   one-time password in the app, and it installs its key and `check.sh` and
   wipes the password. If I use root or a sudo user, the app warns me and I
   must acknowledge.
2. **Dashboard glance** — I open the app and see every host with its worst
   severity from its *last* check and how long ago that was. Hosts that are
   unreachable or stale are shown as such, never as healthy.
3. **Quick check** — I tap "Check now", confirm with my fingerprint, and see
   issues in under 10 seconds: disk at 96%, a failed unit, SSH password auth
   still on. If the check couldn't read something, it says so.
4. **Advice** — Each issue may carry one line of advice that I can read and copy
   to run myself at a terminal. The app never runs it.
5. **Through a jump host** — I onboard an internal host via a bastion; the
   bastion only relays and doesn't appear as a monitored host.
6. **Lost phone** — From a trusted machine I can remove the device key from my
   hosts (clear the agent's `authorized_keys`); on the phone, panic revoke wipes everything
   local.

## 7. Success Metrics

How each metric is measured, and the scenarios that must pass for v1, are in
[docs/ACCEPTANCE.md](docs/ACCEPTANCE.md).

- **Install-to-first-check:** fresh host to first check results in under 5
  minutes, with no admin script required.
- **Check latency:** under 10 seconds from biometric confirmation to results
  on a reachable host.
- **Privacy:** a network audit shows no traffic except SSH to the user's own
  hosts.
- **Read-only:** a host-side audit of a full session shows no command other than
  the documented ones (§5.2) and no file changes beyond the app's footprint.
- **False positives:** at most 20 per 100 flagged issues during dogfooding.
- **Dogfood:** the author uses it weekly and reaches for it instead of a
  laptop for "what's wrong?"

## 8. Risks & Mitigations

| Risk | Mitigation |
|---|---|
| Compromised or buggy `check.sh` hides a problem or shows misleading text | It cannot make the phone run anything (§5.2); `coverage` shows what wasn't read; the script is installed and refreshed by the app, and its integrity is the operator's host-security responsibility |
| Compromised jump host tries to MITM inner hosts | Per-hop host key pinning; key changes refused |
| Heuristic false positives or false "all clear" | Real-filesystem filtering, tests with fixtures, `coverage` reporting; false-positive rate tracked during dogfooding |
| Setup password exposure | Used once, held only in memory, wiped after key install whether setup succeeds or fails |
| Operator connects as root / passwordless sudo | Elevation detected at onboarding; explicit acknowledgment, recorded in the audit log |
| Lost or stolen phone | Key needs biometric for every use and is invalidated by biometric re-enrollment; no unattended key use (invariant 2); panic revoke and clearing `authorized_keys` on the host |
| Installed `check.sh` drifts from the app's version | App replaces it when its bundled version is newer; `scripts/verify_check_sh_sync.sh` guards copies in the repo |
| Biometric fatigue when checking many hosts | Accepted for v1 (one confirmation per host per check); see open question Q1 |

## 9. Status

The check script, multi-host dashboard and key/host-pinning layer are
implemented. The 2026-11-12 narrowing (D5–D8) means a large part of the shipped
app — the older Logs/Analyzer screens, the on-device model, the Proxmox API code,
the action catalog and its sudoers helper — is to be removed, plus background
polling (D1). §11 lists that work. Nothing has been verified on a physical device
yet; the first priority is to find out whether SSH signing works on one (G2).

## 10. Decisions

| Topic | Decision |
|---|---|
| Core agent | Shell script (`check.sh`), not a daemon; run by the phone on demand |
| Recommended identity | `stackward-agent`: dedicated low-privilege Linux user, no sudo |
| Jump host role | Relay only, not a monitored host |
| Distribution | Signed dogfood APK on GitHub Releases, installed with `adb install -r`; no Play Store |
| **D1 (2026-10-23) Background checks** | **None.** Checks run only in-app after biometric. Considered and not chosen: a second, server-restricted key (`authorized_keys` `restrict,command="bash ~/.stackward/check.sh"`) usable unattended for polling and alerts — the path to revisit if background monitoring comes back. |
| **D2 (2026-10-23) `check.sh` delivery** | The app installs and updates it during onboarding as the SSH user. |
| **D5 (2026-11-12) v1 is read-only** | The app runs no actions on hosts. Suggestions become per-issue advice text, never executed. This supersedes D3 and D4, the phone-side action catalog and risk gate, and the earlier "gated improvements" scope. |
| **D6 (2026-11-12) Remove the older subsystems** | The Logs screen, Log Analyzer, hourly log digest, Gemma-proposed shell commands (`PermissionEngine`, tiers, `CapabilityPack`), and the Proxmox API/token code are deleted from the app (kept in git history). |
| **D7 (2026-11-12) No on-device model in v1** | No summaries, no model download or import. Can return after v1 as an optional layer, with its own prompt-injection, integrity and evaluation work. |
| **D8 (2026-11-12) Linux hosts only** | Proxmox and Docker hosts are checked as plain Linux hosts. Proxmox- and Docker-specific checks are future work and need a privilege design first. |
| Considered, not chosen (2026-11-12) | A tool-calling model with a server-side API, voice control and iOS (issue #103). Different architecture, with a server component to build and secure; revisit only after v1 proves the core job. |

## 11. Spec ahead of code (open work as of 2026-11-12)

| # | Gap | Where |
|---|---|---|
| G1 | Remove background SSH: per-host "Auto 4h" polling (`CheckWorker`, `PollingMode`), critical notifications (`CheckNotifier`, `POST_NOTIFICATIONS`), and the hourly `LogDigestWorker` | `check/`, `logs/`, `ui/dashboard/`, manifest |
| G2 | Bind each SSH signature to a biometric prompt. The Keystore key requires per-use authentication, but `BiometricGate` authenticates without a `CryptoObject`, so signing likely fails on Android 13+ devices with a strong biometric enrolled. **Verify on a device first.** | `crypto/AgentKeyManager`, `ui/security/BiometricGate`, `connection/` |
| G3 | Onboarding installs `check.sh` (bundled as an app asset) and refreshes it when the bundled version is newer; extend `scripts/verify_check_sh_sync.sh` to cover the bundled copy | `onboarding/OnboardingFlow` |
| G4 | Delete the stale bundled `assets/scripts/bootstrap_linux.sh` and the unused `BootstrapRunner` | `app/src/main/assets/scripts/`, `onboarding/` |
| R1 | **Remove the older subsystems (D6, D7, D8):** Logs and Analyzer screens, `logs/`, `inference/` (incl. the MediaPipe dependency and model download), `proxmox/`, `permissions/` tiering (`PermissionEngine`, `CapabilityPack`, `Tier1Rules`), related Settings and navigation | `ui/`, `logs/`, `inference/`, `proxmox/`, `permissions/`, `build.gradle.kts` |
| R2 | **Make the app read-only (D5):** replace `suggestions[]`, `CheckActionCatalog`, `CheckSuggestionGate`, the Apply/confirm flow and audited execution with per-issue `advice`; add `coverage` to the `check.sh` output; drop the `stackward-check-action` and `stackward-onetimer` helpers and the catalog/helper parity guard | `check/`, `ui/dashboard/`, `scripts/` |
| R3 | `check.sh` accuracy: exclude pseudo/loop filesystems, escape JSON, report `coverage`, fix mismatched thresholds and the stale header (the check.sh cleanup issue) | `scripts/check.sh` + embedded copy |
| R4 | Dashboard honesty: unreachable and stale states, "check all" | `ui/dashboard/` |
| R5 | Docs: after R1/R2, update `docs/ARCHITECTURE.md`, `docs/PHASES.md`, `docs/USER_STORIES.md`, `docs/ACCEPTANCE.md` and the README so they describe only what ships | docs |

**Open questions**

- **Q1** — Checking many hosts means one biometric confirmation per host.
  Acceptable for v1, or is a batched "check all" worth a different key design?
- **Q2** — Devices that can't create a biometric-bound Keystore key (below
  Android 13 or no strong biometric) fall back to a software key, which can't
  enforce invariant 2. Refuse onboarding on such devices, or allow it with an
  explicit warning?
