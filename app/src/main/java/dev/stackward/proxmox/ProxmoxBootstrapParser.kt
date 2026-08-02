package dev.stackward.proxmox

import org.json.JSONObject

/**
 * Parses Proxmox API token output from [bootstrap_proxmox.sh].
 */
object ProxmoxBootstrapParser {

    private val markerRegex = Regex("""STACKWARD_TOKEN_JSON=(\{.*\})""")

    fun parse(stdout: String): ProxmoxTokenCredentials? {
        val json = markerRegex.find(stdout)?.groupValues?.get(1) ?: return null
        return runCatching { parseJson(json) }.getOrNull()
    }

    private fun parseJson(json: String): ProxmoxTokenCredentials {
        val root = JSONObject(json)
        val data = root.optJSONObject("data") ?: root
        val fullTokenId = data.optString("full-tokenid").ifBlank {
            data.optString("tokenid")
        }
        val secret = data.optString("value").ifBlank {
            data.optString("secret")
        }
        require(fullTokenId.isNotBlank() && secret.isNotBlank()) {
            "Proxmox token JSON missing full-tokenid or secret"
        }
        return ProxmoxTokenCredentials(tokenId = fullTokenId, tokenSecret = secret)
    }
}

data class ProxmoxTokenCredentials(
    val tokenId: String,
    val tokenSecret: String,
)
