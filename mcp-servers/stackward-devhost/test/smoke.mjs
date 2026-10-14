// Manual smoke test: spawns the server over stdio and calls its
// no-network tool. Not part of the app's Gradle test suite — this is a
// dev-tool sanity check, run with `node test/smoke.mjs`.
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const __dirname = dirname(fileURLToPath(import.meta.url));

const transport = new StdioClientTransport({
  command: "node",
  args: [join(__dirname, "..", "src", "index.js")],
});

const client = new Client({ name: "smoke-test", version: "0.0.1" });
await client.connect(transport);

const tools = await client.listTools();
console.log(
  "tools:",
  tools.tools.map((t) => t.name),
);

const result = await client.callTool({ name: "run_check_script_locally", arguments: {} });
console.log("run_check_script_locally isError:", result.isError);
console.log(result.content[0].text);

const hostsResult = await client.callTool({ name: "list_dev_hosts", arguments: {} });
console.log("list_dev_hosts:", hostsResult.content[0].text);

await client.close();
process.exit(0);
