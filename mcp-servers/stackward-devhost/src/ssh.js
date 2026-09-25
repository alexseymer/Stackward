// Minimal promise wrapper around ssh2 for one-shot exec calls against a
// dev/test host. No interactive shell, no port forwarding, no command
// construction from free-form strings — every caller in index.js passes a
// fixed command string assembled from validated, non-string parameters.
import { Client } from "ssh2";
import { readFileSync } from "node:fs";
import { homedir } from "node:os";
import { resolve } from "node:path";
import { verifyOrPin } from "./knownHosts.js";

const DEFAULT_TIMEOUT_MS = 20_000;

function expandHome(path) {
  if (!path) return path;
  return path.startsWith("~") ? resolve(homedir(), path.slice(1).replace(/^\/+/, "")) : path;
}

/**
 * @param {object} conn - { host, port, username, privateKeyPath, passphrase }
 * @param {string} command - fixed command string, never built from raw user text
 * @param {number} [timeoutMs]
 * @returns {Promise<{exitCode: number|null, stdout: string, stderr: string}>}
 */
export function sshExec(conn, command, timeoutMs = DEFAULT_TIMEOUT_MS) {
  return new Promise((resolvePromise, reject) => {
    const client = new Client();
    let settled = false;
    const hostKey = `${conn.host}:${conn.port ?? 22}`;

    const timer = setTimeout(() => {
      if (settled) return;
      settled = true;
      client.destroy();
      reject(new Error(`SSH command timed out after ${timeoutMs}ms`));
    }, timeoutMs);

    const finish = (fn, arg) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      client.end();
      fn(arg);
    };

    client
      .on("error", (err) => finish(reject, err))
      .on("ready", () => {
        client.exec(command, (err, stream) => {
          if (err) return finish(reject, err);
          let stdout = "";
          let stderr = "";
          stream
            .on("close", (exitCode) => {
              finish(resolvePromise, { exitCode, stdout, stderr });
            })
            .on("data", (data) => {
              stdout += data.toString("utf8");
            });
          stream.stderr.on("data", (data) => {
            stderr += data.toString("utf8");
          });
        });
      });

    let privateKey;
    try {
      privateKey = readFileSync(expandHome(conn.privateKeyPath));
    } catch (err) {
      return finish(reject, new Error(`Cannot read private key at ${conn.privateKeyPath}: ${err.message}`));
    }

    client.connect({
      host: conn.host,
      port: conn.port ?? 22,
      username: conn.username,
      privateKey,
      passphrase: conn.passphrase || undefined,
      readyTimeout: timeoutMs,
      // Trust-on-first-use pinning, same principle the Stackward app itself
      // applies to every host it monitors. See src/knownHosts.js.
      hostHash: "sha256",
      hostVerifier: (hashHex) => {
        const result = verifyOrPin(hostKey, hashHex);
        if (!result.ok) {
          finish(reject, new Error(result.detail));
          return false;
        }
        return true;
      },
    });
  });
}
