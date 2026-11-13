# Threat model

Scope: Stackward v1 as defined in [PRD.md](../PRD.md): an Android app that, after a
biometric confirmation, opens SSH to the user's own Linux hosts, runs `~/.stackward/check.sh`
and shows the result. Read-only; no model; no background activity. Written 2026-11-13 against
the code on `main`; the review pass at the bottom is a first pass, not an audit.

## Assets

| Asset | Where it lives | Why it matters |
|---|---|---|
| Device SSH key (Ed25519) | Android Keystore on API 33+; on API 28–32 or without enrolled strong biometrics, a software key in `EncryptedSharedPreferences` (`crypto/AgentKeyManager.kt`) | Grants a shell as the agent user on every host |
| Host access | The agent user's `authorized_keys` on each host | Read access to journal, Docker, process state |
| Pinned host keys | `HostKeyPinStore` (encrypted prefs) | Detects MITM on later connects |
| Last check results, last failures | `CheckResultStore` (encrypted prefs) | Reveals the hosts' state and names |
| Audit log | `AuditLogRepository` (encrypted prefs, last 200 entries) | Records rotations, revokes, elevation acknowledgments |
| One-time setup password / pasted key | `SessionSecretVault` (memory only) | Full login to a host during onboarding |

## Actors and trust boundaries

- **The user** holding an unlocked phone: trusted.
- **A person with the phone** (lost, stolen, borrowed, unlocked): partly trusted by the OS, not by us.
- **A monitored host**: trusted to be *yours*, **not** trusted for what it prints. It controls `check.sh` output and every log line.
- **A jump host**: relays bytes; must not be able to impersonate inner hosts.
- **A network attacker** on the path to a host or jump host.
- **Other apps** on the phone and anyone who can read an unencrypted backup or a rooted filesystem.
- **The build and release chain** (GitHub Actions, dependencies, the signing key).

## Threats

Status: **Mitigated** = enforced in code with a test or a directly checkable property; **Partly** = mitigated with a stated gap; **Open** = not mitigated, tracked.

| # | Threat | Mitigation and where enforced | Proof | Status / residual risk |
|---|---|---|---|---|
| T1 | A compromised or buggy host makes the phone run something (fake "advice", unknown JSON fields) | The app has no code path that executes host-supplied text. `advice` is rendered in a `SelectionContainer` for a human; legacy `suggestions[]` is ignored by `CheckScriptParser`. The catalog, Apply UI and sudo helpers are deleted (#117, #118, #126). | `CheckScriptParserTest.parse_ignoresLegacySuggestions`; `grep -rn sudo app/src/main` shows only onboarding text | **Mitigated** |
| T2 | The host lies about its own health ("no issues" while broken) | Cannot be prevented from the phone. `coverage` makes *blindness* visible (denied / absent / unknown); failures and staleness are shown, not hidden (#90). | `CheckModelsTest` (coverage gaps), `HostHealthTest`, `check_sh_test.sh` coverage cases | **Partly**: a fully compromised host can report anything |
| T3 | Prompt injection through log text | There is no model (PRD D7); log text is shown only through Compose `Text`, never interpreted as markup or commands. | Code removed in #117 | **Mitigated** by absence |
| T4 | MITM on the first connection (trust on first use) | Fingerprint pinned on first use; a changed key fails closed and keeps the old pin; pins are per host:port and per jump-host hop. | `TofuHostKeyVerifierTest` | **Partly**: the first connect is unauthenticated; the user has to compare the fingerprint out of band if the network is hostile |
| T5 | Compromised jump host impersonates inner hosts | Inner host key is pinned separately from the jump host's; both are verified (`SshConnectionManager.connectViaJumpHost`). | No dedicated test (needs a fake SSH server) | **Partly** |
| T6 | Stolen or borrowed phone is used to reach hosts | Keystore key requires a fresh biometric per use and is invalidated by biometric re-enrollment (API 33+). Panic revoke wipes local state and empties the agent's `authorized_keys`; an admin can do the same from the host (`docs/INSTALL.md`). | Panic revoke has no automated test (needs Keystore/Context) | **Open for API 28–32 and devices without strong biometrics**: software key, no per-use prompt (#85, #95) |
| T7 | Setup password leaks | Held only in `SessionSecretVault` (AES key ephemeral, memory only), wiped after key install whether setup succeeds or fails; never written to disk or logs. | `SessionSecretVaultTest` | **Mitigated** (an attacker who can read process memory is out of scope) |
| T8 | User onboards with root / a sudo-capable account | Elevation is detected at login and requires an explicit acknowledgment that is audit-logged. | `LoginProbeResultTest` (probe parsing) | **Partly**: it is a warning, not a prohibition (PRD §5, "No broad privilege") |
| T9 | Privilege escalation through the app's host-side helpers | None remain: `bootstrap_linux.sh` installs no sudoers rule or `/usr/local/sbin` helper (#126). Rotation/revoke edit the agent's own `authorized_keys`; the command builders quote every value. | `AuthorizedKeysCommandsTest` (runs the commands under `sh`, incl. hostile quoting) | **Mitigated**. Hosts bootstrapped earlier keep unused old helpers until the admin removes them (see #126) |
| T10 | Tampered or outdated `check.sh` on a host | The app installs its bundled copy and replaces it when the host reports an older `version`; three repo copies are CI-compared. Script content travels base64-encoded and is written atomically with mode 700. | `CheckScriptRunnerTest`, `CheckScriptBundleTest`, `verify_check_sh_sync.sh` | **Partly**: a host attacker who can edit `~/.stackward/check.sh` can change its output (see T2); the app does not detect same-version edits |
| T11 | Other apps or backups read stored data | `allowBackup="false"`; all stores use `EncryptedSharedPreferences` (AES-256) with a Keystore master key; the only exported component is the launcher activity. | Manifest | **Partly**: a rooted device or an unlocked-phone attacker with debugging access is out of scope; the software key (T6) is as strong as that master key |
| T12 | Audit/result data survives a wipe | `PanicRevokeService.wipeLocalState` clears keys, profiles, pins, health, settings, results and the audit log. | Not tested (needs Context) | **Partly** |
| T13 | Lock-screen exposure of host state | The app posts no notifications (removed in #119). | Manifest has no `POST_NOTIFICATIONS` | **Mitigated** by absence |
| T14 | Malicious update of the app itself | Android requires the same signing key for updates, but release APKs are signed with `app/dogfood.keystore`, which is committed to the repo, so anyone can produce an accepted update. | None | **Open** (#125). Acceptable only while the app is for the author's own devices |
| T15 | Compromised dependency or CI action | Dependabot PRs, CodeQL, shellcheck, `submit-gradle` dependency graph. Dependency versions are pinned in Gradle; Actions are referenced by tag, not by commit SHA. | CI | **Partly**: not reviewed in depth |
| T16 | Model download tampering | There is no model. | Code removed in #117 | **Mitigated** by absence |

## Not in scope for v1

Hosts you do not control; a malicious operator of your own phone's OS; an attacker who already has root on
the monitored host (they own the data anyway); denial of service against the phone or a host.

## First review pass (2026-11-13)

Read: `AgentKeyManager`, `SecurePrefs`, `SessionSecretVault`, `TofuHostKeyVerifier`, the
`bootstrap_linux.sh` helpers, `KeyRotationService`, `PanicRevokeService`, the release script and
workflow headers, the manifest.

Findings filed:

- **#124** (fixed in #126): rotation and panic revoke relied on sudo helpers that contradicted PRD invariant 4 and hard-coded the user name.
- **#125**: release APKs are signed with a committed keystore (T14). Needs a maintainer decision.
- **#85 / #95** (already open): per-use biometric signing may never prompt on Android 13+, and the silent software-key fallback (T6).

Not yet reviewed: `SshConnectionManager` authentication and retry paths in depth (it retries a failed command
up to three times, which also repeats a failing authentication), the release workflow's secret handling,
and an on-device test of any of the biometric claims above. Mitigation rows that say "Not tested" should
get an instrumented test once a device is available.
