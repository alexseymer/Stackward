package dev.stackward.proxmox

import dev.stackward.connection.SshConnectionManager
import dev.stackward.crypto.ProxmoxTokenStore
import dev.stackward.onboarding.HostType
import dev.stackward.onboarding.ServerProfile
import org.json.JSONArray
import org.json.JSONObject

/**
 * Proxmox REST API client. Reaches :8006 through an SSH direct tunnel so the
 * API token never appears in remote process listings.
 */
class ProxmoxApiClient(
    private val ssh: SshConnectionManager,
    private val tokenStore: ProxmoxTokenStore,
) {

    suspend fun hasToken(): Boolean = tokenStore.getToken() != null

    suspend fun verifyConnection(profile: ServerProfile): String {
        val nodes = listNodes(profile)
        return "Proxmox API OK — ${nodes.size} node(s): ${nodes.joinToString()}"
    }

    suspend fun buildDigest(profile: ServerProfile): String {
        if (profile.hostType != HostType.PROXMOX) {
            return "Host is not Proxmox."
        }
        val token = tokenStore.getToken()
            ?: return "Proxmox API token not stored — re-run onboarding."

        return runCatching {
            withClient(profile, token) { client ->
                val nodes = fetchNodeNames(client)
                if (nodes.isEmpty()) {
                    return@withClient "No Proxmox nodes visible."
                }

                buildString {
                    nodes.take(2).forEach { node ->
                        appendLine("Node: $node")
                        appendVmSection(client, node, "qemu", "VMs")
                        appendVmSection(client, node, "lxc", "LXCs")
                        appendTaskSection(client, node)
                        appendLine()
                    }
                }.trim()
            }
        }.getOrElse { error ->
            "Proxmox API error: ${error.message}"
        }
    }

    suspend fun execute(profile: ServerProfile, method: String, apiPath: String): String {
        val token = tokenStore.getToken()
            ?: throw ProxmoxException("Proxmox API token not stored")
        return withClient(profile, token) { client ->
            client.request(method.uppercase(), apiPath).bodyOrThrow()
        }
    }

    private suspend fun listNodes(profile: ServerProfile): List<String> {
        val token = tokenStore.getToken()
            ?: throw ProxmoxException("Proxmox API token not stored")
        return withClient(profile, token) { client ->
            fetchNodeNames(client)
        }
    }

    private suspend fun <T> withClient(
        profile: ServerProfile,
        token: Pair<String, String>,
        block: (ProxmoxHttpClient) -> T,
    ): T {
        return ssh.withProxmoxTunnel(profile) { tunnel ->
            val client = ProxmoxHttpClient(
                tunnel = tunnel,
                hostHeader = "${profile.host}:${profile.proxmoxPort}",
                tokenId = token.first,
                tokenSecret = token.second,
            )
            block(client)
        }
    }

    private fun fetchNodeNames(client: ProxmoxHttpClient): List<String> {
        val body = client.request("GET", "nodes").bodyOrThrow()
        val data = JSONObject(body).optJSONArray("data") ?: JSONArray()
        return buildList {
            for (index in 0 until data.length()) {
                val node = data.getJSONObject(index)
                add(node.getString("node"))
            }
        }
    }

    private fun StringBuilder.appendVmSection(
        client: ProxmoxHttpClient,
        node: String,
        kind: String,
        label: String,
    ) {
        val body = runCatching {
            client.request("GET", "nodes/$node/$kind").bodyOrThrow()
        }.getOrElse { error ->
            appendLine("  $label: ${error.message}")
            return
        }
        val items = JSONObject(body).optJSONArray("data") ?: JSONArray()
        if (items.length() == 0) {
            appendLine("  $label: none")
            return
        }
        appendLine("  $label (${items.length()}):")
        for (index in 0 until minOf(items.length(), 10)) {
            val item = items.getJSONObject(index)
            val vmid = item.optInt("vmid", -1)
            val name = item.optString("name", "vm$vmid")
            val status = item.optString("status", "unknown")
            appendLine("    - $vmid $name [$status]")
        }
    }

    private fun StringBuilder.appendTaskSection(client: ProxmoxHttpClient, node: String) {
        val body = runCatching {
            client.request("GET", "nodes/$node/tasks?limit=10").bodyOrThrow()
        }.getOrElse { error ->
            appendLine("  Recent tasks: ${error.message}")
            return
        }
        val tasks = JSONObject(body).optJSONArray("data") ?: JSONArray()
        if (tasks.length() == 0) {
            appendLine("  Recent tasks: none")
            return
        }
        appendLine("  Recent tasks:")
        for (index in 0 until minOf(tasks.length(), 5)) {
            val task = tasks.getJSONObject(index)
            val type = task.optString("type", "?")
            val status = task.optString("status", "?")
            val id = task.optString("upid", task.optString("id", "?"))
            appendLine("    - $type [$status] $id")
        }
    }
}
