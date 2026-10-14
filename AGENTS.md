# Stackward — Agent Guide

Stackward is a **single-module Android app** (`:app`, Kotlin + Jetpack Compose,
applicationId `dev.stackward`). It is an on-device LLM agent for monitoring/managing
self-hosted infra (Linux/Proxmox/Docker) over SSH + the Proxmox REST API. There is
no backend, database, docker-compose, or web/dev server — the "product" is the client
APK plus the user's own external infrastructure. See `README.md`, `PRD.md`, and
`docs/`.

## Product direction & source of truth

The Lookout pivot is now **implemented, not just planned** — all six
`STRATEGY.md` Implementation Order steps are built, build-verified
(`:app:testDebugUnitTest`/`:app:assembleDebug`/`:app:lintDebug` all green),
and past a `/simplify` + `/code-review` pass. Docs were updated to match on
2026-09-26; none of the "superseded" banners from before that date apply
anymore.

1. **`STRATEGY.md`** — the chosen north star ("Lookout": read-only triage
   via `~/.stackward/check.sh`, risk-based safe/risky/scary gating, model
   optional) and the Implementation Order status.
2. **`PRD.md`** — full spec matching that strategy.
3. **`docs/PHASES.md`, `docs/ARCHITECTURE.md`, `docs/USER_STORIES.md`,
   the README "Status" table** — now describe the **current** architecture
   (check.sh + dashboard + risk-gated actions) as primary, with the
   pre-pivot Tier 1/2/3 + CapabilityPack system kept as a clearly-marked
   "legacy" subsystem — it's still real, functional code
   (`PermissionEngine`, `AgentKeyManager`, host-key TOFU pinning, the Logs
   screen's Gemma summarization), reused by the new direction at the
   connection/credential layer and for the optional AI summary, just no
   longer the primary flow. Trust these docs' current-state claims; if a
   change here makes them wrong, update them in the same commit rather
   than letting them drift again.
4. **`scripts/check.sh`** — detects disk/memory/service/security/log
   issues on a host and returns JSON (`issues[]`, `suggestions[]` with
   `risk ∈ safe|risky|scary`). Installed onto monitored hosts by
   `scripts/bootstrap_linux.sh`, which embeds it verbatim — run
   `scripts/verify_check_sh_sync.sh` after touching either file; it caught
   real drift once already.
5. **`dev.stackward.check.CheckActionCatalog`** — the actual security
   boundary for check.sh suggestions: phone-side, fixed, never trusts a
   suggestion's self-reported risk. A RISKY action's command must be
   reachable by the `stackward-agent` SSH user — currently via the
   `stackward-check-action` sudoers helper (`scripts/bootstrap_linux.sh`),
   which independently re-validates the action id. Adding a new RISKY
   catalog entry means adding a matching case there too, or it will
   silently fail against a real host (this exact bug shipped once and was
   caught by `/code-review`, not by unit tests).

`CapabilityPack.kt` should currently only expose `MONITOR` — that's the
*legacy* subsystem's scope, unrelated to check.sh's own risk gate. If you
see `MAINTAIN`/`PROVISION` reappear without a deliberate decision to widen
scope, that's a regression.

## MCP servers

`.mcp.json` at the repo root registers **stackward-devhost**
(`mcp-servers/stackward-devhost/`) — a dev-only Node MCP server for
iterating on `scripts/check.sh` without touching the Android app:

- `run_check_script_locally` — runs `scripts/check.sh` right here (no SSH,
  no host needed) and validates its JSON against the PRD schema. Use this
  for the fast loop while changing detection logic.
- `list_dev_hosts`, `check_host`, `run_diagnostic` — optional, SSH-based
  tools for validating against a real dev/test host. Require
  `mcp-servers/stackward-devhost/hosts.json` (gitignored; copy from
  `hosts.example.json`). Never accept free-form shell — `run_diagnostic`
  only runs a hardcoded allowlist mirroring check.sh's own commands, and
  host keys are TOFU-pinned the same way the app itself pins them.

First use in a session needs `npm install` inside
`mcp-servers/stackward-devhost/` (its `node_modules` is gitignored and not
committed). See that directory's README for the full tool list and setup.

## Subagents

`.claude/agents/project-strategist.md` (Claude Code) and
`.cursor/agents/projekt-stratege.md` (Cursor, responds in German) do the
same job: read STRATEGY.md/PRD.md against the actual repo state and
recommend prioritized next steps. They analyze and recommend only — they
don't implement. Invoke proactively at the start of a session, after large
changes, or whenever it's unclear whether check.sh, the app, or docs are
next.

## Cursor Cloud specific instructions

### What the environment provides (already installed in the VM snapshot)
- **JDK 17** at `/usr/lib/jvm/java-17-openjdk-amd64` (matches CI). Gradle is pinned to
  it via `~/.gradle/gradle.properties` (`org.gradle.java.home=...`), so `./gradlew`
  uses JDK 17 even though the base image also has JDK 21. Don't rely on `JAVA_HOME`.
- **Android SDK** at `~/android-sdk` (`ANDROID_HOME` exported in `~/.bashrc`):
  `platform-tools`, `platforms;android-37.0` (this is `compileSdk = 37`),
  `build-tools;37.0.0`. AGP also auto-installs `build-tools;36.0.0` on first build.
- **Gradle 9.5.0** via the wrapper (`./gradlew`); distribution is cached under `~/.gradle`.
- `local.properties` (gitignored) points Gradle at the SDK via `sdk.dir`. The update
  script recreates it if missing.

### Build / test / lint (the full dev workflow, mirrors `.github/workflows/ci.yml`)
- Unit tests (core logic): `./gradlew :app:testDebugUnitTest`
- Android lint: `./gradlew :app:lintDebug` (report: `app/build/reports/lint-results-debug.html`)
- Debug APK: `./gradlew :app:assembleDebug` (output: `app/build/outputs/apk/debug/app-debug.apk`, ~180 MB)
- Shell scripts are linted separately with `shellcheck -x scripts/*.sh app/src/main/assets/scripts/*.sh`.

### Gotchas / non-obvious notes
- **No GUI/emulator testing here.** The VM has no `/dev/kvm`, so a hardware-accelerated
  Android emulator won't run and software emulation is not practical. Verify changes
  headlessly with the unit suite (`:app:testDebugUnitTest`) + `:app:assembleDebug`.
  The app's core safety logic (`permissions.PermissionEngine` tier classification,
  `inference.ActionProposalParser`, `proxmox.ProxmoxCommands`) is plain JVM code fully
  covered by unit tests, so that is the primary end-to-end check.
- **First build is slow** (~1.5 min each): it downloads the Gradle distribution and all
  Maven deps, and AGP downloads `build-tools;36.0.0`. Subsequent builds reuse the daemon/cache.
- Genuinely exercising the app end-to-end (SSH into a host, on-device Gemma inference)
  additionally requires a physical Android device, an SSH-reachable Linux host
  provisioned via `scripts/bootstrap_linux.sh`, and a multi-GB Gemma `.task`/`.litertlm`
  model file (see `docs/MODEL_SETUP.md`). None of that is available in this VM.
- Git workflow: never push to `main`; use a `cursor/…` branch + draft PR and request
  review from `alexseymer` (see `.cursor/rules/git-workflow.mdc`).

### Phone test builds (debug APK releases)

When the user says **"cut a new build"** (or asks for a test APK release), run:

```bash
./scripts/release_apk.sh
```

This script (on the `builds` branch only — never `main`):

1. Resets `builds` to latest `origin/main`
2. Bumps `versionCode` / `versionName` in `app/build.gradle.kts`
3. Runs `./gradlew :app:assembleDogfood` (aborts with Gradle output on failure)
4. Verifies the APK signature with `apksigner` before publishing
5. Commits `chore: bump version to vX.Y.Z`, pushes `builds`, and creates a GitHub
   Release tagged `vX.Y.Z` with `app-dogfood.apk` attached
6. Prints the direct APK download URL **and reminds the user to install via
   `adb install -r`** (Chrome sideload often fails with "App not installed" under
   Android 2026 developer verification — see `docs/INSTALL.md`)

All release APKs are signed with the shared `app/dogfood.keystore` (committed;
dogfood-only, not for Play Store) so installs upgrade consistently across builds.

Use `DRY_RUN=1 ./scripts/release_apk.sh` to preview the version bump without
building or publishing. Optional `SOURCE_REF=origin/<branch>` builds from another ref.
`BUILD_TYPE=smoke ./scripts/release_apk.sh` publishes a no-native-libs install probe.
