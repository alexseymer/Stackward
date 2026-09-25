#!/usr/bin/env node
// stackward-devhost — MCP server for developing/validating Stackward's
// check.sh anomaly detector, without touching the Android app.
//
// Two jobs, both read-only:
//  1. Run scripts/check.sh locally (no host needed) and validate the JSON
//     it produces against the PRD's schema — fast iteration loop.
//  2. Run check.sh (or a small allowlisted set of the same diagnostics it
//     uses internally) against a real dev/test host over SSH, so an agent
//     can sanity-check detection logic against live infrastructure.
//
// This server intentionally cannot run arbitrary commands: every SSH tool
// either invokes the fixed check.sh path or picks from a hardcoded
// diagnostic allowlist. That mirrors Stackward's own product invariant —
// structured, human-legible actions only, never raw shell from a prompt.
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { z } from "zod";
import { execFile } from "node:child_process";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import { promisify } from "node:util";
import { sshExec } from "./ssh.js";
import { getHost, listHostAliases } from "./hosts.js";

const execFileAsync = promisify(execFile);
const __dirname = dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = join(__dirname, "..", "..", "..");
const CHECK_SCRIPT_PATH = join(REPO_ROOT, "scripts", "check.sh");

const server = new McpServer({
  name: "stackward-devhost",
  version: "0.1.0",
});

// Diagnostics mirror exactly what scripts/check.sh runs internally — see
// detect_disk_issues / detect_memory_issues / detect_service_issues /
// detect_security_issues / detect_log_errors in that file. Keep these two
// lists in sync when check.sh changes.
const DIAGNOSTICS = {
  disk: "df -h",
  memory: "cat /proc/meminfo | head -5",
  failed_services: "systemctl list-units --state=failed --no-pager --plain",
  ssh_password_auth: "grep -iE '^[[:space:]]*PasswordAuthentication' /etc/ssh/sshd_config || true",
  listening_ports: "ss -tlnp 2>/dev/null || netstat -tlnp 2>/dev/null || true",
  journal_errors: "journalctl -n 100 --priority=err --no-pager 2>/dev/null || true",
  docker_exited: "docker ps --filter status=exited --format '{{.Names}}' 2>/dev/null || true",
};

function validateCheckJson(raw) {
  let parsed;
  try {
    parsed = JSON.parse(raw);
  } catch (err) {
    return { valid: false, error: `Not valid JSON: ${err.message}`, parsed: null };
  }
  const problems = [];
  if (typeof parsed.timestamp !== "string") problems.push('"timestamp" must be a string');
  if (typeof parsed.hostname !== "string") problems.push('"hostname" must be a string');
  if (!Array.isArray(parsed.issues)) problems.push('"issues" must be an array');
  if (!Array.isArray(parsed.suggestions)) problems.push('"suggestions" must be an array');
  for (const issue of parsed.issues ?? []) {
    if (!issue.type || !issue.severity || !issue.message) {
      problems.push(`issue missing type/severity/message: ${JSON.stringify(issue)}`);
    }
  }
  for (const suggestion of parsed.suggestions ?? []) {
    if (!suggestion.id || !suggestion.risk || !suggestion.action) {
      problems.push(`suggestion missing id/risk/action: ${JSON.stringify(suggestion)}`);
    } else if (!["safe", "risky", "scary"].includes(suggestion.risk)) {
      problems.push(`suggestion "${suggestion.id}" has invalid risk "${suggestion.risk}" (expected safe/risky/scary)`);
    }
  }
  return { valid: problems.length === 0, error: problems.join("; ") || null, parsed };
}

server.registerTool(
  "run_check_script_locally",
  {
    title: "Run check.sh locally",
    description:
      "Executes scripts/check.sh on this machine (no SSH, no remote host) and validates the JSON " +
      "it prints against the PRD schema (timestamp, hostname, issues[], suggestions[]). Use this for " +
      "the fast local dev loop while iterating on check.sh logic.",
    inputSchema: {},
  },
  async () => {
    try {
      const { stdout, stderr } = await execFileAsync("bash", [CHECK_SCRIPT_PATH], { timeout: 15_000 });
      const validation = validateCheckJson(stdout);
      return {
        content: [
          {
            type: "text",
            text: JSON.stringify(
              {
                ok: validation.valid,
                validationError: validation.error,
                stderr: stderr || undefined,
                issueCount: validation.parsed?.issues?.length ?? 0,
                suggestionCount: validation.parsed?.suggestions?.length ?? 0,
                parsed: validation.parsed,
              },
              null,
              2,
            ),
          },
        ],
        isError: !validation.valid,
      };
    } catch (err) {
      return {
        content: [{ type: "text", text: `Failed to run check.sh: ${err.message}` }],
        isError: true,
      };
    }
  },
);

server.registerTool(
  "list_dev_hosts",
  {
    title: "List configured dev hosts",
    description:
      "Lists host aliases configured in mcp-servers/stackward-devhost/hosts.json (gitignored). " +
      "Never returns key paths or passphrases.",
    inputSchema: {},
  },
  async () => ({
    content: [{ type: "text", text: JSON.stringify(listHostAliases(), null, 2) }],
  }),
);

server.registerTool(
  "check_host",
  {
    title: "Run check.sh on a real dev host",
    description:
      "SSHes into a host configured in hosts.json, runs ~/.stackward/check.sh (the path the app " +
      "queries), and validates the JSON response. Requires the host to already have check.sh " +
      "installed (scripts/bootstrap_linux.sh does this).",
    inputSchema: {
      hostAlias: z.string().describe("Alias from hosts.json, see list_dev_hosts"),
    },
  },
  async ({ hostAlias }) => {
    try {
      const conn = getHost(hostAlias);
      const { exitCode, stdout, stderr } = await sshExec(conn, "bash ~/.stackward/check.sh");
      const validation = validateCheckJson(stdout);
      return {
        content: [
          {
            type: "text",
            text: JSON.stringify(
              {
                ok: exitCode === 0 && validation.valid,
                exitCode,
                validationError: validation.error,
                stderr: stderr || undefined,
                issueCount: validation.parsed?.issues?.length ?? 0,
                suggestionCount: validation.parsed?.suggestions?.length ?? 0,
                parsed: validation.parsed,
              },
              null,
              2,
            ),
          },
        ],
        isError: !(exitCode === 0 && validation.valid),
      };
    } catch (err) {
      return {
        content: [{ type: "text", text: `check_host failed: ${err.message}` }],
        isError: true,
      };
    }
  },
);

server.registerTool(
  "run_diagnostic",
  {
    title: "Run one read-only diagnostic on a dev host",
    description:
      "Runs a single hardcoded, read-only diagnostic command on a configured host — the same " +
      "commands check.sh itself uses (disk, memory, failed_services, ssh_password_auth, " +
      "listening_ports, journal_errors, docker_exited). There is no free-form command option: " +
      "this tool cannot execute arbitrary shell, by design.",
    inputSchema: {
      hostAlias: z.string().describe("Alias from hosts.json, see list_dev_hosts"),
      diagnostic: z.enum(Object.keys(DIAGNOSTICS)).describe("Which check.sh-equivalent diagnostic to run"),
    },
  },
  async ({ hostAlias, diagnostic }) => {
    try {
      const conn = getHost(hostAlias);
      const command = DIAGNOSTICS[diagnostic];
      const { exitCode, stdout, stderr } = await sshExec(conn, command);
      return {
        content: [{ type: "text", text: JSON.stringify({ exitCode, stdout, stderr }, null, 2) }],
        isError: exitCode !== 0,
      };
    } catch (err) {
      return {
        content: [{ type: "text", text: `run_diagnostic failed: ${err.message}` }],
        isError: true,
      };
    }
  },
);

const transport = new StdioServerTransport();
await server.connect(transport);
