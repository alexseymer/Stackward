# PRD: Stackward — Personal Infrastructure Triage & Gated Improvements

**Revised 2026-10-23** — checked claim-by-claim against the shipped code and
updated with product decisions D1–D4 (§10). Where this spec now asks for
something the code doesn't do yet, §11 lists the gap. Treat §11 as the
authoritative "spec is ahead of code" list.

## 1. Summary

An Android app for solo infrastructure operators to triage and improve their
own systems — Linux hosts, Proxmox, Docker — from a phone without a laptop or
SaaS exposure.

**The job:** "Is something wrong right now? Should I open the laptop?"

**How it works:**
1. One-time setup: the app connects to a host over SSH, installs its own key
   and the check script `~/.stackward/check.sh`, then forgets the setup secret.
2. When the user opens a host and confirms with biometric, the phone runs
   `check.sh` over SSH.
3. The script returns **structured issues and suggestions** as JSON (disk
   full, failed service, risky SSH config, …).
4. The phone — not the host — decides each suggestion's risk: safe runs
   immediately, risky needs biometric confirmation, scary is never run and
   shows manual steps instead.
5. Optional: an on-device Gemma model summarizes the results in plain language.

**Product invariants** (always true):

1. **Nothing leaves the device** except over SSH/API to the user's own
   infrastructure. No cloud, no SaaS, no telemetry.
2. **No background access.** Every SSH session to a host happens while the
   user is in the app and has confirmed with biometric. Nothing connects to a
   host on a schedule or while the phone is locked (decision D1).
3. **The phone decides risk.** A suggestion's self-reported risk is
   display-only; only commands in the phone's fixed catalog ever run, and
   unknown actions never run.
4. **The model is optional.** Without Gemma the app is a complete structured
   anomaly checker.

## 2. Goals

- **One-time setup:** connect once with a password or private key; the app
  installs its public key and `check.sh`, verifies key login, and wipes the
  setup secret — never stored.
- **On-demand checks:** run `check.sh` on a host when the user asks; show
  structured issues and suggestions in under 10 seconds.
- **Gate improvements by risk** using the phone's own action catalog (§5.2).
- **Summarize with Gemma (optional):** user-imported Gemma 2B/4B runs
  on-device; without it the structured list stands alone.
- **Reach LAN-only hosts:** jump host (bastion as relay, not as a monitored
  server) and optional port knocking.
- **Keep it private:** see invariant 1. Single operator per installation.

## 3. Non-Goals (v1)

- **No background monitoring or push alerts.** Checks run only while the user
  is in the app (invariant 2). This drops scheduled polling and critical-issue
  notifications. Revisit only with a credential that is safe to use unattended
  (see D1 for the option that was considered).
- **No autonomous actions.** Nothing runs without a user tap, and risky
  actions need biometric.
- **No team/multi-operator mode,** no shared credentials, no accounts.
- **No cloud or SaaS fallback,** including for inference.
- **No broad privilege by default.** The recommended identity is a dedicated
  low-privilege `stackward-agent` user. Connecting as root or a sudo-capable
  user is allowed, but onboarding detects it, warns, and requires explicit
  acknowledgment (recorded in the audit log).
- **No broad actuation.** Creating VMs, editing sudoers, changing Proxmox
  permissions, changing SSH configuration and similar boundary changes are out
  of scope; the app shows what to do and the operator does it at the console.
- **No Play Store in v1.** Distribution is a signed dogfood APK from GitHub
  Releases, installed with `adb install -r`.

## 4. Users

**Primary:** a solo homelab operator — one person, their own Linux / Proxmox /
Docker hosts (typically a handful to a few dozen) — who wants a fast "is
anything wrong?" answer from a phone without exposing logs to a third party.

**Secondary:** none in v1. No small-business or team story. The project is a
personal MIT-licensed tool, not ready for outside contribution.

## 5. Core Concepts

### 5.1 The check script (`~/.stackward/check.sh`)

A dependency-free bash script on each monitored host. It runs unprivileged as
the SSH user, reads system state, and prints one JSON document. It never
changes anything.

**Output contract** (parsed by `CheckScriptParser`):

```json
{
  "timestamp": "2026-10-23T09:14:02Z",
  "hostname": "pve-01",
  "issues": [
    {"type": "disk", "severity": "critical", "message": "/var at 96% capacity"},
    {"type": "security", "severity": "critical", "message": "SSH password authentication enabled"}
  ],
  "suggestions": [
    {"id": "cleanup-disk", "risk": "risky", "action": "cleanup_old_logs", "reason": "Remove old log files to free disk space"},
    {"id": "disable-ssh-pwd", "risk": "scary", "action": "disable_ssh_password_auth", "reason": "Disable SSH password authentication"}
  ]
}
```

`severity` ∈ `critical | high | medium`. A suggestion's `risk` is the host's
opinion and is display-only (§5.2); `action` is the only field the phone acts
on. `reason` is optional.

**What it detects** (defaults; each threshold can be overridden through an
environment variable of the same name):

| Area | Check | Severity |
|---|---|---|
| Disk | any mounted filesystem > `DISK_WARNING_PCT` (85) | high |
| Disk | any mounted filesystem > `DISK_CRITICAL_PCT` (95) | critical |
| Memory | used > `MEM_WARNING_PCT` (80) | high |
| Services | each failed systemd unit | critical |
| Security | `PasswordAuthentication yes` in `sshd_config` | critical |
| Security | more than 20 listening TCP ports | medium |
| Logs | more than 5 `err`-priority entries in the last `SYSTEMD_JOURNAL_LINES` (100) journal lines | medium |
| Docker | any exited containers (if Docker is installed and readable) | medium |

**Not covered yet:** Proxmox task logs, Docker container log contents, swap,
firewall state, service restart counts. These are candidate checks, not v1
requirements.

**Delivery:** the app installs `check.sh` during onboarding as the SSH user
(no root needed) and replaces it when the app's bundled version is newer
(decision D2). The optional admin script `scripts/bootstrap_linux.sh` is only
needed for the privileged helper that risky actions use (§5.2) and for
journal/Docker log read access.

### 5.2 Risk-based action gating

The authority is the phone-side catalog `dev.stackward.check.CheckActionCatalog`.
It maps an action id to a fixed command and a risk level. The phone never
builds a command from anything the host sends.

| Risk | Meaning | Phone behavior |
|---|---|---|
| **Safe** | Read-only, no side effects | Runs immediately; audited |
| **Risky** | Changes state or needs elevation; recoverable | Shows the literal command; runs only after biometric; audited |
| **Scary** | Changes access or security boundaries, or could lock the operator out | Never run by the app; shows manual console steps |
| **Unknown** | Action id not in the catalog | Never run; treated as scary |

**v1 catalog (shipped):**

| Action id | Risk | Command / behavior |
|---|---|---|
| `tail_journal` | Safe | `journalctl -n 100 --no-pager` |
| `list_failed_services` | Safe | `systemctl list-units --state=failed --no-pager --plain` |
| `cleanup_old_logs` | Risky | `sudo /usr/local/sbin/stackward-check-action cleanup_old_logs` (vacuums journal entries older than 7 days) |
| `disable_ssh_password_auth` | Scary | Manual steps only, to be done at the console — never over the SSH connection being changed (decision D3) |

**Candidate actions** (not in v1): restart a named service, reboot host,
apply package updates. Rules for adding any action:
- A **risky** action must run through the `stackward-check-action` sudoers
  helper, which re-checks the action id against its own fixed list. Adding a
  catalog entry without a matching helper case fails silently on a real host.
- No action may take free-form arguments from the host. A parameterized
  action (e.g. "restart *this* service") needs its own design for validating
  the parameter on both the phone and the helper.

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
5. Optional: run `scripts/bootstrap_linux.sh` as root to enable risky actions
   and log read access.

**Keys and biometric:**
- The device SSH key lives in Android Keystore (hardware-backed where
  available), cannot be exported, and requires biometric for every use. Each
  SSH session therefore requires one biometric confirmation, which is what
  enforces invariant 2.
- Biometric data never leaves the device; it only unlocks local key use.
- Panic revoke removes the key from hosts it can still reach and wipes all
  local credentials, pins, results, and schedules. Key rotation is supported.

**Proxmox:** a Proxmox host can additionally provide an API token at
onboarding (stored in Keystore) for the legacy Proxmox features (§5.5). It is
not used by `check.sh`.

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

### 5.5 Legacy subsystem (still shipping)

The pre-Lookout features remain in the app and follow the same invariants:
the **Logs** screen (log digest, heuristic anomaly detection, Gemma
summaries), the **Log Analyzer**, Proxmox API views, and the older
`PermissionEngine` tier model for Gemma-proposed actions — restricted to the
read-only `MONITOR` capability pack. The check-script dashboard is the primary
flow; these are kept because they reuse the same connection, credential, and
inference layers. The legacy hourly background log digest violates invariant 2
and is removed (§11).

## 6. Key User Stories (v1 acceptance)

1. **First connect** — I create `stackward-agent`, enter the host and the
   one-time password in the app, and it installs its key and `check.sh` and
   wipes the password. If I use root or a sudo user, the app warns me and I
   must acknowledge.
2. **Dashboard glance** — I open the app and see every host with its worst
   severity from its *last* check and how long ago that was. Tap a host for
   details.
3. **Quick check** — I tap "Check now", confirm with my fingerprint, and see
   issues and suggestions in under 10 seconds: disk at 96%, a failed unit, SSH
   password auth still on.
4. **Plain-language summary (optional)** — With a Gemma model imported I get a
   short summary; without one I still get the full structured list.
5. **Safe action** — I tap "Review logs"; it runs immediately and shows the
   last 100 journal lines.
6. **Risky action** — I tap "Clean up old logs"; the app shows the exact
   command, I confirm with biometric, it runs, and the result is in the audit
   log.
7. **Scary action** — The app flags SSH password auth but shows "Manual step
   required" with console instructions, and never runs it.
8. **Through a jump host** — I onboard an internal host via a bastion; the
   bastion only relays and doesn't appear as a monitored host.
9. **Lost phone** — From a trusted machine I can remove the device key from my
   hosts (`stackward-revoke-key`); on the phone, panic revoke wipes everything
   local.

## 7. Success Metrics

- **Install-to-first-check:** fresh host to first check results in under 5
  minutes, with no model and no admin script required.
- **Check latency:** under 10 seconds from biometric confirmation to results
  on a reachable host.
- **Model optional:** the app is useful with no model; with Gemma, a summary
  arrives within 30 seconds on a 2026 flagship.
- **Privacy:** a network audit shows no traffic except SSH/API to the user's
  own hosts.
- **False positives:** at most 20 per 100 flagged issues during dogfooding.
- **Dogfood:** the author uses it weekly and reaches for it instead of a
  laptop for "what's wrong?"

## 8. Risks & Mitigations

| Risk | Mitigation |
|---|---|
| Compromised or buggy `check.sh` returns malicious suggestions | Host risk labels are ignored; only fixed catalog commands run; unknown actions never run; risky actions show the literal command and need biometric; all runs audited |
| Compromised jump host tries to MITM inner hosts | Per-hop host key pinning; key changes refused |
| Heuristic false positives | Nothing runs without a tap; risky actions still need biometric |
| Setup password exposure | Used once, held only in memory, wiped after key install whether setup succeeds or fails |
| Operator connects as root / passwordless sudo | Elevation detected at onboarding; explicit acknowledgment, recorded in the audit log |
| Lost or stolen phone | Key needs biometric for every use and is invalidated by biometric re-enrollment; no unattended key use (invariant 2); panic revoke and server-side `stackward-revoke-key` |
| Installed `check.sh` drifts from the app's version | App replaces it when its bundled version is newer; `scripts/verify_check_sh_sync.sh` guards copies in the repo |
| Biometric fatigue when checking many hosts | Accepted for v1 (one confirmation per host per check); see open question Q1 |
| No Gemma model | Structured results are complete on their own |

## 9. Status

The Lookout direction (check script, multi-host dashboard, phone-side risk
gating, optional Gemma summary) is implemented and on `main`. This revision
changes three things the code doesn't do yet — no background checks (D1),
`check.sh` installed by the app (D2), and biometric-bound key use — listed
with the related cleanups in §11. The current focus is dogfooding on a
physical device against real hosts.

## 10. Decisions

| Topic | Decision |
|---|---|
| Core agent | Shell script (`check.sh`), not a daemon; run by the phone on demand |
| Recommended identity | `stackward-agent`: dedicated low-privilege Linux user, no sudo |
| Risk authority | The phone's fixed catalog; host-reported risk is display-only |
| Gemma | Optional and user-imported; never required |
| Scary operations | Out of scope; manual console steps shown |
| Jump host role | Relay only, not a monitored host |
| v1 scope | Read-only checks plus a small catalog of gated improvements; no Maintain/Provision |
| Distribution | Signed dogfood APK on GitHub Releases, installed with `adb install -r`; no Play Store |
| **D1 (2026-10-23) Background checks** | **None.** Checks run only in-app after biometric. Considered and not chosen: a second, server-restricted key (`authorized_keys` `restrict,command="bash ~/.stackward/check.sh"`) usable unattended for polling and alerts — the path to revisit if background monitoring comes back. |
| **D2 (2026-10-23) `check.sh` delivery** | The app installs and updates it during onboarding as the SSH user; the admin script is optional (risky-action helper, log access). |
| **D3 (2026-10-23) SSH password auth** | Scary: manual steps only, never run by the app (lockout risk). |
| **D4 (2026-10-23) Action set** | The spec lists the shipped catalog; other actions are candidates subject to the §5.2 rules. |

## 11. Spec ahead of code (open work as of 2026-10-23)

| # | Gap | Where |
|---|---|---|
| G1 | Remove background SSH: the per-host "Auto 4h" polling (`CheckWorker`, `PollingMode.AUTO_4H`), critical notifications (`CheckNotifier`, `POST_NOTIFICATIONS`), and the legacy hourly `LogDigestWorker` | `check/`, `logs/`, `ui/dashboard/`, manifest |
| G2 | Bind each SSH signature to a biometric prompt. The Keystore key requires per-use authentication, but `BiometricGate` authenticates without a `CryptoObject`, so signing likely fails on Android 13+ devices with a strong biometric enrolled. **Verify on a device first.** | `crypto/AgentKeyManager`, `ui/security/BiometricGate`, `connection/` |
| G3 | Onboarding installs `check.sh` (bundled as an app asset) and refreshes it when the bundled version is newer; extend `scripts/verify_check_sh_sync.sh` to cover the bundled copy | `onboarding/OnboardingFlow` |
| G4 | Delete the stale bundled `assets/scripts/bootstrap_linux.sh` (144 lines, no `check.sh`, no risky-action helper) and the unused `BootstrapRunner` that loads it | `app/src/main/assets/scripts/`, `onboarding/` |
| G5 | `check.sh`: its header wrongly says onboarding runs the bootstrap script; it labels `disable_ssh_password_auth` as `risky` (harmless, since the phone ignores it, but confusing) | `scripts/check.sh` + embedded copy |
| G6 | Once G1 lands: update `docs/ARCHITECTURE.md`, `docs/PHASES.md`, `docs/USER_STORIES.md`, and the README, which still describe auto-polling and notifications as current | docs |

**Open questions**

- **Q1** — Checking many hosts means one biometric confirmation per host.
  Acceptable for v1, or is a batched "check all" worth a different key design?
- **Q2** — Devices that can't create a biometric-bound Keystore key (below
  Android 13 or no strong biometric) fall back to a software key, which can't
  enforce invariant 2. Refuse onboarding on such devices, or allow it with an
  explicit warning?
