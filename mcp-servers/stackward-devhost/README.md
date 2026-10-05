# stackward-devhost (MCP server)

A dev-only MCP server for iterating on `scripts/check.sh` — the host-side
anomaly detector the Stackward app queries over SSH (see `STRATEGY.md` and
`PRD.md §5.0`). It is **not part of the Android app** and ships nothing to
the APK; it exists so a Claude Code / Cursor session can validate check.sh
output without a human manually SSH-ing in and eyeballing JSON.

## Why this shape

Stackward's own product invariant is "structured, human-legible actions
only — never raw shell from a prompt" (see `PRD.md §2`). This server holds
itself to the same rule:

- It never accepts a free-form shell command.
- `run_diagnostic` only runs one of a fixed set of commands that are
  copy-identical to what `check.sh` itself runs internally.
- `check_host` only ever runs `bash ~/.stackward/check.sh` — nothing else.
- SSH host keys are pinned trust-on-first-use into a local
  `known_hosts.json`, same principle as `AgentKeyManager`/TOFU in the app.
  A changed key fails closed with an explanation, it does not silently
  reconnect.

## Setup

```bash
cd mcp-servers/stackward-devhost
npm install
cp hosts.example.json hosts.json   # only needed for the two SSH-based tools
```

Edit `hosts.json` with your dev/test host(s). `privateKeyPath` must point
at a key already installed in that host's `authorized_keys` (e.g. via
`scripts/bootstrap_linux.sh`). Never put passwords here — the app's own
onboarding model (password once, then key-only) applies to this tool too.

`hosts.json` and `known_hosts.json` are gitignored; never commit real host
details or keys.

## Tools

| Tool | Needs a host? | What it does |
|------|----------------|--------------|
| `run_check_script_locally` | No | Runs `scripts/check.sh` on the machine running the MCP server and validates the JSON against the PRD schema (`timestamp`, `hostname`, `issues[]`, `suggestions[]`, `suggestions[].risk ∈ {safe,risky,scary}`). Fastest loop for check.sh development. |
| `list_dev_hosts` | No | Lists configured host aliases (never keys/passphrases). |
| `check_host` | Yes | SSHes to a configured host, runs `~/.stackward/check.sh` (must already be installed there), validates the response. |
| `run_diagnostic` | Yes | Runs one hardcoded read-only diagnostic (`disk`, `memory`, `failed_services`, `ssh_password_auth`, `listening_ports`, `journal_errors`, `docker_exited`) — the same commands check.sh uses — for debugging a single detector in isolation. |

## Registering with Claude Code

Already wired into the repo root's `.mcp.json`. Claude Code picks it up
automatically for sessions opened in this repo. If you need to re-add it
manually elsewhere:

```json
{
  "mcpServers": {
    "stackward-devhost": {
      "command": "node",
      "args": ["mcp-servers/stackward-devhost/src/index.js"]
    }
  }
}
```

## Smoke test

```bash
npm run smoke
```

Spawns the server, lists its tools, and runs `run_check_script_locally`
against the repo's actual `scripts/check.sh` — no host or `hosts.json`
required.

## Keeping this in sync with check.sh

If you add a new detector to `scripts/check.sh` (new `issue.type` or a new
suggestion), mirror the equivalent read-only command into the `DIAGNOSTICS`
map in `src/index.js` so `run_diagnostic` stays useful for debugging it.
