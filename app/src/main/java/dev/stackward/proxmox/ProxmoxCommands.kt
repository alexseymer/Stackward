package dev.stackward.proxmox

/**
 * Proxmox API paths allowed per permission tier.
 *
 * Commands use the form `METHOD path` where path is relative to `/api2/json/`.
 */
object ProxmoxCommands {
    private val tier1GetPrefixes = listOf(
        "nodes/",
    )

    val tier2PowerActions = setOf(
        "start",
        "stop",
        "shutdown",
        "reboot",
        "reset",
        "suspend",
        "resume",
    )

    fun isTier1Read(command: String): Boolean {
        val (method, path) = parse(command) ?: return false
        if (method != "GET") return false
        if (path == "nodes" || path.startsWith("nodes/")) {
            return tier1GetPrefixes.any { prefix -> path.startsWith(prefix) || path == "nodes" }
        }
        return false
    }

    fun isTier2Power(command: String): Boolean {
        val (method, path) = parse(command) ?: return false
        if (method != "POST") return false
        val powerPattern = Regex(
            """^nodes/[^/]+/(qemu|lxc)/\d+/status/(${tier2PowerActions.joinToString("|")})$""",
        )
        return powerPattern.matches(path)
    }

    fun isTier3Blocked(command: String): Boolean {
        val (_, path) = parse(command) ?: return true
        val blocked = listOf(
            "/config",
            "/allocate",
            "access/",
            "storage/",
            "pools/",
        )
        return blocked.any { fragment -> path.contains(fragment, ignoreCase = true) }
    }

    fun parse(command: String): Pair<String, String>? {
        val trimmed = command.trim()
        val space = trimmed.indexOf(' ')
        if (space <= 0) return null
        val method = trimmed.substring(0, space).uppercase()
        val path = trimmed.substring(space + 1).removePrefix("/api2/json/").trim('/')
        if (path.isBlank()) return null
        return method to path
    }
}
