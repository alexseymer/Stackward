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

// Mirrors the JSON shape scripts/check.sh must produce — see PRD.md §5.0.
const CheckResultSchema = z.object({
  timestamp: z.string(),
  hostname: z.string(),
  issues: z.array(
    z.object({
      type: z.string().min(1),
      severity: z.string().min(1),
      message: z.string().min(1),
    }),
  ),
  suggestions: z.array(
    z.object({
      id: z.string().min(1),
      risk: z.enum(["safe", "risky", "scary"]),
      action: z.string().min(1),
      reason: z.string().default(""),
    }),
  ),
});

function validateCheckJson(raw) {
  let json;
  try {
    json = JSON.parse(raw);
  } catch (err) {
    return { valid: false, error: `Not valid JSON: ${err.message}`, parsed: null };
  }
  const result = CheckResultSchema.safeParse(json);
  if (!result.success) {
    const error = result.error.issues.map((issue) => `${issue.path.join(".") || "(root)"}: ${issue.message}`).join("; ");
    return { valid: false, error, parsed: null };
  }
  return { valid: true, error: null, parsed: result.data };
}

// Shared response shape for the two tools that run check.sh and validate its
// output (run_check_script_locally, check_host) — they differ only in what
// "ok" depends on and which extra fields (stderr, exitCode) they attach.
function checkResultFields(validation, { ok = validation.valid, ...extra } = {}) {
  return {
    ok,
    validationError: validation.error,
    issueCount: validation.parsed?.issues?.length ?? 0,
    suggestionCount: validation.parsed?.suggestions?.length ?? 0,
    parsed: validation.parsed,
    ...extra,
  };
}

function toToolResult(fields) {
  return {
    content: [{ type: "text", text: JSON.stringify(fields, null, 2) }],
    isError: !fields.ok,
  };
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
      return toToolResult(checkResultFields(validation, { stderr: stderr || undefined }));
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
      return toToolResult(
        checkResultFields(validation, {
          ok: exitCode === 0 && validation.valid,
          exitCode,
          stderr: stderr || undefined,
        }),
      );
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
