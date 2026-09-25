// Loads dev/test host connection details from a local, gitignored
// hosts.json so credentials never end up in .mcp.json or in git. See
// hosts.example.json for the format.
import { readFileSync, existsSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const __dirname = dirname(fileURLToPath(import.meta.url));
const HOSTS_PATH = join(__dirname, "..", "hosts.json");

export function loadHosts() {
  if (!existsSync(HOSTS_PATH)) return {};
  const parsed = JSON.parse(readFileSync(HOSTS_PATH, "utf8"));
  return parsed.hosts ?? {};
}

export function getHost(alias) {
  const hosts = loadHosts();
  const entry = hosts[alias];
  if (!entry) {
    const known = Object.keys(hosts);
    throw new Error(
      known.length > 0
        ? `Unknown host alias "${alias}". Configured: ${known.join(", ")}`
        : `Unknown host alias "${alias}". No hosts configured — copy hosts.example.json to hosts.json first.`,
    );
  }
  return entry;
}

export function listHostAliases() {
  return Object.entries(loadHosts()).map(([alias, entry]) => ({
    alias,
    host: entry.host,
    port: entry.port ?? 22,
    username: entry.username,
  }));
}
