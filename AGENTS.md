# Stackward — Agent Guide

Stackward is a **single-module Android app** (`:app`, Kotlin + Jetpack Compose,
applicationId `dev.stackward`). It is an on-device LLM agent for monitoring/managing
self-hosted infra (Linux/Proxmox/Docker) over SSH + the Proxmox REST API. There is
no backend, database, docker-compose, or web/dev server — the "product" is the client
APK plus the user's own external infrastructure. See `README.md`, `PRD.md`, and
`docs/`.

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
