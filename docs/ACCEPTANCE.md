# Stackward — Acceptance Criteria

> **Scope narrowed 2026-11-12 ([PRD](../PRD.md) D5–D8):** v1 is a read-only health check
> for Linux hosts. Scenarios for actions, the on-device model, Proxmox and the older
> log features were removed; the read-only guarantee (INV-3) replaced the action
> scenarios. Statuses marked *Built* describe the code **today** (the removal work R1/R2
> is done); *Verified* stays empty until someone runs the scenario on a device.

This is the definition of "done" for v1. A scenario passes only when it has been
**run on a real phone against a real host** and the result is recorded. Code that
exists is not accepted code.

- [PRD.md](../PRD.md) says *what* the product must do (invariants §1, metrics §7).
- This document says *how we decide it does*: scenarios with measurable pass criteria.
- [USER_STORIES.md](USER_STORIES.md) keeps the per-story detail and the **Built** status.
  Where the two disagree on whether something works, this document wins.

## 1. How to read and update this document

Every scenario has two independent statuses:

| Column | Meaning | Who sets it |
|---|---|---|
| **Built** | The code for it exists today (✅ yes · ⚠️ partly · ❌ no). | Whoever changes the code. |
| **Verified** | Someone ran the scenario on a device and it met the pass criteria. Record it in the [sign-off log](#9-sign-off-log) with date, build version, device and host. | Only a device test. A green CI run never sets this. |

A scenario is **accepted** when Verified is ✅. Re-verify it after any change to the
code it covers (the sign-off log shows the build it was last verified on).

Scenario IDs are stable: `INV` (product invariants), `ONB` (onboarding), `CHK`
(running a check), `ACT` (actions), `FAIL` (failure handling), `SEC` (recovery and
security), `Q` (quality). Open issues reference them.

## 2. Test setup

| Item | Requirement |
|---|---|
| **Primary phone** | Android 13+ with a strong biometric (fingerprint or face) enrolled, so the biometric-bound Keystore key is used. Record the model and Android version. |
| **Second phone** | Android 9–12 **or** a phone with no enrolled biometric, to exercise the fallback ([#95](https://github.com/alexseymer/Stackward/issues/95)). |
| **Low-end phone** | Optional: a lower-mid-range device to check that CHK-3 (latency) still holds. The README states the minimum tested Android version. |
| **Host A** | A fresh Debian/Ubuntu VM with only OpenSSH, systemd and a new `stackward-agent` user. **No** admin script run. |
| **Host B** | Host A plus `scripts/bootstrap_linux.sh` run as root (journal access, Docker ACL; no sudo rules are installed). |
| **Bastion** | A second SSH host that can reach Host A, for the jump-host scenario. |
| **Observer** | Anything that can see the phone's traffic and the hosts' `auth.log` (a router or laptop hotspot with `tcpdump`, plus shell access to the hosts). |
| **Build** | A signed dogfood APK from a GitHub Release, installed with `adb install -r`. Record the version. |

## 3. Product invariants

These are the four rules in PRD §1. Each needs a scenario because they are the
promises the app makes; most of them are currently untested.

| ID | Scenario | Pass criteria | Built | Verified |
|---|---|---|---|---|
| **INV-1** | **Nothing leaves the device.** *Given* the phone is behind the observer, *when* I onboard, run checks, run check all, and rotate the key, *then* … | The only destinations contacted are the test hosts/bastion (and DNS for their names). No analytics, no cloud endpoints. Model download is the only exception and is tested separately in INV-1b. | ✅ (no model download, no network code besides SSH) | ⬜ |
| **INV-2** | **No SSH without me present.** *Given* auto-polling was enabled in an older build and I upgrade, *when* the app is closed and the phone locked for 24 h, *then* … | Zero `stackward-agent` logins in the hosts' `auth.log` for the whole period; `adb shell dumpsys jobscheduler` lists no Stackward jobs; no notification posted. | ⚠️ (workers removed in #119 and old jobs cancelled once on upgrade; device ⬜) | ⬜ |
| **INV-2b** | **Every session follows a biometric prompt.** *When* I tap "Check now", run an action, or run "Check all", *then* … | A biometric prompt appears before each SSH connection; cancelling it opens no connection (no `auth.log` entry); logcat shows the prompt before the connect. | ❌ ([#85](https://github.com/alexseymer/Stackward/issues/85)) | ⬜ |
| **INV-3** | **Read-only.** *Given* a host with auditd (or `strace -f` on `sshd` children) recording commands and file changes, *when* I onboard, run several checks, rotate the key and use panic revoke, *then* … | The only commands executed are `bash ~/.stackward/check.sh`, the onboarding/login-probe steps, and the key install/rotation/revoke steps. No file changes beyond `~/.stackward/check.sh` and `authorized_keys`. A `check.sh` edited to print a command as `advice`, or to claim anything else, causes nothing to run on the host. | ✅ (no code path executes host text; no sudo helpers — #118, #126) | ⬜ |

## 4. Core flow

### Onboarding

| ID | Scenario | Pass criteria | Built | Verified |
|---|---|---|---|---|
| **ONB-1** | **First check from the app alone.** *Given* Host A and a clean app, *when* I onboard with the one-time password and then tap "Check now", *then* … | Results appear with no admin script run. Wall-clock from "start setup" to first results is **under 5 minutes** (median of 3 runs on a fresh host). | ⚠️ (app installs check.sh, #122; not run on a real host) | ⬜ |
| **ONB-2** | **The setup secret is not kept.** *When* setup succeeds and when it fails, *then* … | The password/private key is gone from app storage, logs and the process after setup (check with `adb shell run-as` and a logcat grep for the secret). | ✅ | ⬜ |
| **ONB-3** | **Elevated account needs acknowledgment.** *Given* I connect as root or a passwordless-sudo user, *then* … | The app warns, will not continue until acknowledged, and records the acknowledgment in the audit log. | ✅ | ⬜ |
| **ONB-4** | **Jump host.** *When* I onboard Host A through the bastion, *then* … | It works; the bastion is not listed as a monitored host; each hop's host key is pinned separately. | ✅ | ⬜ |
| **ONB-5** | **Port knock.** *Given* a host that opens SSH only after a knock sequence, *then* … | The app connects after knocking. | ✅ | ⬜ |
| **ONB-6** | **Host key pinning.** *When* the host's key changes, *then* … | The connection is refused with a clear message and **not** silently re-pinned. A deliberate re-pin path exists. | ⚠️ (refuse ✅, re-pin UI ❌) | ⬜ |

### Checking

| ID | Scenario | Pass criteria | Built | Verified |
|---|---|---|---|---|
| **CHK-1** | **Healthy host.** *When* I check a healthy Host B, *then* … | Result shows no issues; row is green with "last checked" time. No false critical (in particular no "Unit 0 failed"). | ⚠️ (fixture tests in CI; device ⬜) | ⬜ |
| **CHK-2** | **Real problems are found.** *Given* I fill a filesystem past 95%, stop a service into failed state, and enable SSH password auth, *then* … | One critical disk issue, one critical service issue naming the unit, one critical security issue, all within one check. Each clears after the problem is fixed and I re-check. | ✅ | ⬜ |
| **CHK-3** | **Latency.** *When* I tap "Check now" on a LAN host, *then* … | Results in **under 10 seconds** from the end of the biometric prompt to results shown, at the 90th percentile of 10 runs. Repeat via the bastion and report separately. | ⚠️ | ⬜ |
| **CHK-4** | **Many hosts.** *Given* 5 hosts, *when* I check all, *then* … | Every host reports its own outcome; one slow or dead host does not block the others. | ✅ (Check all, sequential — #121) | ⬜ |
| **CHK-5** | **No false green from blind spots.** *Given* Host A (agent user not in `systemd-journal`, Docker not readable), *when* I check, *then* … | The result and the dashboard say what could not be read. It is not shown as plain "No issues". | ✅ (`coverage` — #118; device ⬜) | ⬜ |
| **CHK-6** | **No false alarms from normal Linux quirks.** *Given* a host with snap loop mounts and a mount point containing a quote, *then* … | No disk alert for the loop mounts; valid JSON; a quoted mount name yields valid JSON. | ✅ (fixtures — #120) | ⬜ |

### Actions

Removed from v1 (PRD D5). The app runs no actions; see INV-3 for the read-only guarantee.

## 5. Failure handling

A tool people reach for when something is wrong must be clear when *it* is the
thing that is wrong.

| ID | Scenario | Pass criteria | Built | Verified |
|---|---|---|---|---|
| **FAIL-1** | **Host unreachable.** *Given* Host B is powered off, *when* I check, *then* … | A distinct "unreachable" state (not green, not "never checked"), with the reason. It is still shown after leaving and reopening the app. | ✅ (persisted failures — #121) | ⬜ |
| **FAIL-2** | **Stale result.** *Given* a host last checked more than 24 h ago, *then* … | The row visibly says the result is stale, with its age. | ✅ (24 h staleness — #121) | ⬜ |
| **FAIL-3** | **Key rejected.** *Given* the host removed the app's key, *when* I check, *then* … | A clear "authentication failed" state with the next step. No retry storm. | ⚠️ | ⬜ |
| **FAIL-4** | **`check.sh` missing or broken.** *Given* the script is deleted, or prints invalid JSON, *then* … | A specific message ("not installed", "invalid output") and the remedy. No crash. | ✅ (missing script triggers install; invalid output reported — #122) | ⬜ |
| **FAIL-5** | **Biometric cancelled or unavailable.** *When* I cancel the prompt, or the biometric is removed from the phone, *then* … | Nothing runs; the message explains what happened; the app stays usable. | ❌ ([#85](https://github.com/alexseymer/Stackward/issues/85), [#95](https://github.com/alexseymer/Stackward/issues/95)) | ⬜ |
| **FAIL-6** | **Phone without a biometric-bound key.** *Given* the second phone, *when* I onboard, *then* … | The key is created sealed under a password (min. 10 characters); every check session asks for it; a wrong password is rejected and 5 wrong attempts stop. Never a silent weaker mode. | ❌ ([#95](https://github.com/alexseymer/Stackward/issues/95)) | ⬜ |

## 6. Security and recovery

| ID | Scenario | Pass criteria | Built | Verified |
|---|---|---|---|---|
| **SEC-1** | **Panic revoke.** *When* I trigger it on the phone, *then* … | Server-side key removed where reachable; all local keys, profiles, pins, results and audit entries gone; the app returns to onboarding. | ✅ | ⬜ |
| **SEC-2** | **Lost phone.** *When* an admin clears `~stackward-agent/.ssh/authorized_keys` on a host (see `docs/INSTALL.md`), *then* … | The phone can no longer log in to that host (verify from the phone). | ✅ | ⬜ |
| **SEC-3** | **Key rotation.** *When* I rotate the key, *then* … | The new key works, the old one is rejected by the host. | ✅ | ⬜ |
| **SEC-5** | **Audit log.** *When* I rotate the key, then export the log, *then* … | Every key rotation, panic revoke and elevation acknowledgment appears with time, command and outcome. | ✅ | ⬜ |

## 7. Optional on-device summary

Removed from v1 (PRD D7).

## 8. Quality

| ID | Scenario | Pass criteria | Built | Verified |
|---|---|---|---|---|
| **Q-3** | **False positives.** | Over **2 weeks** on real hosts, log every flagged issue as real or false. At most **20 false per 100 flagged**, with at least 30 flagged issues in the sample. | ❌ (no log yet) | ⬜ |
| **Q-4** | **Localization and accessibility.** | German and English complete; the onboarding → dashboard → host-detail path is usable with TalkBack and at 200% font size. ([#100](https://github.com/alexseymer/Stackward/issues/100)) | ⚠️ (de/en for all screens; TalkBack and font scale untested — #100) | ⬜ |
| **Q-5** | **Install.** | A fresh phone installs the release APK with `adb install -r` and upgrades the previous release in place without losing data. | ⚠️ | ⬜ |
| **Q-6** | **Dogfood.** | The author uses the app weekly for 4 weeks on their own hosts and records whether it replaced opening a laptop. | ❌ | ⬜ |

## 9. Sign-off log

Add a row each time a scenario is run. Newest first. A scenario is only as verified
as its latest row; a failing row reopens it.

| Date | Scenario | Result | Build | Phone / Android | Host | Notes / evidence |
|---|---|---|---|---|---|---|
| — | — | — | — | — | — | Nothing has been verified on a device yet. |

## 10. v1 release gate

v1 is acceptable when **all** of these are Verified ✅ on the current release build:

- Every `INV-*` scenario.
- `ONB-1`, `ONB-2`, `ONB-6`.
- `CHK-1`, `CHK-2`, `CHK-3`, `CHK-5`.
- `FAIL-1`, `FAIL-2`, `FAIL-5`, `FAIL-6`.
- `SEC-1`, `SEC-2`.
- `Q-3`, `Q-5`.

Everything else is desirable for v1 but may be waived explicitly, with the waiver and
its reason written in the sign-off log.

## 11. Where the open work is tracked

| Issue | Scenarios it must satisfy |
|---|---|
| [#85](https://github.com/alexseymer/Stackward/issues/85) SSH signing never prompts | INV-2b, FAIL-5, CHK-3 |
| [#86](https://github.com/alexseymer/Stackward/issues/86) Remove background SSH | INV-2 |
| [#87](https://github.com/alexseymer/Stackward/issues/87) Onboarding installs `check.sh` | ONB-1 |
| [#88](https://github.com/alexseymer/Stackward/issues/88) Blind spots reported as all clear | CHK-5 |
| [#89](https://github.com/alexseymer/Stackward/issues/89) `check.sh` correctness | CHK-1, CHK-6 |
| [#90](https://github.com/alexseymer/Stackward/issues/90) Unreachable and stale hosts | FAIL-1, FAIL-2, CHK-4 |
| [#93](https://github.com/alexseymer/Stackward/issues/93) Test gaps | supports INV-3, SEC-1 |
| [#95](https://github.com/alexseymer/Stackward/issues/95) No biometric-bound key | FAIL-5, FAIL-6 |
| [#100](https://github.com/alexseymer/Stackward/issues/100) Localization and accessibility | Q-4 |
