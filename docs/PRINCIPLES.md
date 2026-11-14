# Design and development principles — the Linux principle

Stackward follows the **Unix/Linux way** of building software, in design and in process. This is a core
principle of the project, not a style preference: when a change conflicts with it, the change needs a written
reason (in the PR) or it does not go in. The PRD says *what* Stackward does; this file says *how* it is built.

> Small, focused programs that work together, plus open, collaborative, iterative development.

## 1. Design (the Unix philosophy)

| Principle | What it means here | Where it shows up | Check before merging |
|---|---|---|---|
| **Do one thing and do it well** | The app answers one question: "is anything wrong on my hosts right now?" It changes nothing on them (PRD D5). | Read-only app; actions, Gemma, Proxmox, log screens were removed. | Does the change serve that one question? If not, it is a different program. |
| **Programs that work together** | The pieces are separate and composable: `check.sh` is a standalone program; the app is only one consumer of it. | `bash scripts/check.sh \| jq` works on any host, with no app. The devhost MCP server and the fixture tests use the same script. | Can the new piece be used and tested without the Android app? |
| **Text is the universal interface** | Interfaces between parts are plain text: `check.sh` prints JSON on stdout, the audit log exports JSON, `advice` is text a human reads. | `scripts/check.sh` contract (PRD §5.1), `AuditLogRepository.exportJson`. | No binary or app-specific formats between components. A new field is a JSON field. |
| **Every program is a filter** | `check.sh` reads the system and writes text; it takes env vars (`DISK_WARNING_PCT`, `PROC_MEMINFO`, …), no prompts, no state. | `scripts/check.sh`. Exit code and stdout carry everything. | New scripts: stdin/args/env in, stdout out, errors on stderr. |
| **Small is beautiful** | Prefer deleting to adding. Small files, small functions, few dependencies. | R1/R2 removals; `AGENTS.md` bans reintroducing removed subsystems. | Does the diff shrink or at least not grow the surface? Is a dependency really needed? |
| **Shell scripts for leverage** | Host-side work is shell (`check.sh`, `bootstrap_linux.sh`), because that is what hosts already have. | `scripts/`. | Prefer a POSIX-ish shell script on the host over agent code. |
| **Prototype early** | Build something runnable first, then refine. | Fixture-driven `check.sh`, dogfood APKs before features are polished. | Can it run end to end now, even crudely? |
| **Portability before cleverness** | Work on plain Linux hosts with only `bash`, `df`, `systemctl`, … Degrade instead of requiring tools. | `coverage` reports `absent` instead of failing. | New host dependency? Make it optional and report it in `coverage`. |
| **Data in plain text where possible** | Config and results are readable text (JSON) and exportable. **Exception:** secrets and results on the phone are stored encrypted (security wins over readability); the *export* is plain text. | `CheckResultStore` (encrypted), audit export (JSON). | Plain text by default; encrypt only what is sensitive, and keep an export. |
| **No captive user interface** | The tool never traps the user: the script runs without the app, results are copyable text, the audit log is exportable, and there is no lock-in to Stackward. | `advice` is selectable text; check.sh stands alone. | Can the user get their data and do the job without our UI? |
| **Make it work, make it right, then fast** | Correctness and clarity before optimization. | Tests before cleverness. | No optimization without a measurement. |

Rules of thumb that follow:

- **New host-side behavior = a change to `check.sh` (text in, text out), not new app logic.**
- **If it needs a new screen *and* a new protocol, it is probably out of scope.**
- **Interfaces first:** define the text contract (JSON shape) and a fixture before writing consumers.
- **Fail loudly and in text:** errors are readable messages, never silent fallbacks (see `coverage`, `KeyLockedException`).

## 2. Process (the Linux development model)

| Principle | Practice |
|---|---|
| **Release early, release often** | Small PRs merged to `main` as soon as CI is green; frequent dogfood builds (`scripts/release_apk.sh` / the Release APK workflow). |
| **Many eyes make bugs shallow** | Everything is public and reviewable: threat model (`docs/THREAT_MODEL.md`), acceptance scenarios (`docs/ACCEPTANCE.md`), open issues for every finding. CI (tests, lint, shellcheck, CodeQL) is the first reviewer. |
| **Treat users as co-developers** | The operator running it on their own hosts is the main source of truth: dogfood sign-offs go in the ACCEPTANCE log; bug reports come with the text `check.sh` printed. |
| **Open communication, transparent decisions** | Decisions are written down with date and reason (PRD §10); changes go through PRs with a description of *why*. No private knowledge. |
| **Clear maintainership** | One maintainer decides (`alexseymer`); contributors and agents propose by PR. Nobody pushes to `main`. |
| **Honesty about state** | Claims match reality: "built" is not "verified" (ACCEPTANCE), and the README says what has not been run on a device. |

## 3. How to apply this

- **Reviewing a change:** run through the table in §1 for the rows it touches; note any deliberate exception in the PR.
- **Adding a feature:** first try to express it as `check.sh` output; if the app only has to show text, it is cheap and fits.
- **Removing is a feature.** Every removed screen, dependency or option is a win.
- **Agents (Claude/Cursor) follow the same rules**; see `AGENTS.md`.

Known tensions (documented, not hidden): the Android app is necessarily a UI program, and the phone stores secrets
encrypted rather than as text files. Both are kept as small and as replaceable as the platform allows.
