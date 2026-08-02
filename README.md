# Stackward

On-device (Gemma 4 E2B/E4B) Android agent for monitoring and managing
self-hosted infrastructure — plain Linux hosts, Proxmox, and Docker — over
SSH, with a tiered, human-confirmed permission model. No credentials,
logs, or biometric data ever leave the device except over your own
SSH/API connections to your own infrastructure.

**Stackward** = steward of your stack.

See [PRD.md](PRD.md) for the full product spec, [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)
for the system design, and [docs/PHASES.md](docs/PHASES.md) for the build
order.

## Status

Phase 0/1 and Phase 4 MVP complete. Phase 2 on-device Gemma summarization
via MediaPipe is implemented (import model + summarize logs). See
[docs/MODEL_SETUP.md](docs/MODEL_SETUP.md).

Early scaffold — not yet end-to-end functional. See [docs/PHASES.md](docs/PHASES.md).

## Build locally

```bash
# Requires Android SDK (API 35) and JDK 17+
export ANDROID_HOME=~/Android/Sdk
./gradlew :app:assembleDebug
```

Open the project in Android Studio for emulator/device testing.

## Security model, in short

- **Tier 1 (routine):** read-only or pre-vetted actions, no prompt.
- **Tier 2 (one-timer):** named elevation, explicit confirmation +
  biometric, single-use.
- **Tier 3 (boundary change):** editing what the agent is allowed to do —
  never automated, human-only.

The model never gets raw shell access. It emits structured proposals that
pass through a permission engine before any command reaches a server.
See [PRD.md § 5](PRD.md#5-core-concepts) for details.

## Repo layout

```
PRD.md                  Full product requirements
docs/ARCHITECTURE.md    System design
docs/PHASES.md          Build roadmap
scripts/                Server-side bootstrap scripts (reviewed by user before running)
app/                    Android app source (scaffold)
```

## Contributing / running locally

Not yet ready for external contribution — this is a personal
infrastructure tool. Scaffold will fill in as phases land.
