// Trust-on-first-use host key pinning for the dev-host MCP server, mirroring
// the pinning behavior the Stackward Android app itself implements. Keeps a
// local, gitignored known_hosts.json next to hosts.json. Never auto-repins
// on mismatch — a changed key must be a deliberate, out-of-band decision.
import { readFileSync, writeFileSync, existsSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const __dirname = dirname(fileURLToPath(import.meta.url));
const STORE_PATH = join(__dirname, "..", "known_hosts.json");

function load() {
  if (!existsSync(STORE_PATH)) return {};
  try {
    return JSON.parse(readFileSync(STORE_PATH, "utf8"));
  } catch {
    return {};
  }
}

function save(store) {
  writeFileSync(STORE_PATH, JSON.stringify(store, null, 2) + "\n", "utf8");
}

/**
 * @returns {{ok: true} | {ok: false, reason: "changed"|"error", detail?: string}}
 */
export function verifyOrPin(key, hashHex) {
  const store = load();
  const existing = store[key];

  if (!existing) {
    store[key] = hashHex;
    save(store);
    return { ok: true, pinned: true };
  }

  if (existing === hashHex) {
    return { ok: true, pinned: false };
  }

  return {
    ok: false,
    reason: "changed",
    detail:
      `Host key for "${key}" changed (pinned=${existing}, seen=${hashHex}). ` +
      `This can mean a re-installed OS, or a MITM. Verify out-of-band, then ` +
      `remove the "${key}" entry from mcp-servers/stackward-devhost/known_hosts.json to re-pin.`,
  };
}
