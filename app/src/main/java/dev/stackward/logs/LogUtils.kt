package dev.stackward.logs

object LogTruncate {
    const val DEFAULT_MAX_CHARS = 32_000

    fun truncate(text: String, maxChars: Int = DEFAULT_MAX_CHARS): Pair<String, Boolean> {
        if (text.length <= maxChars) {
            return text to false
        }
        val marker = "\n\n[… truncated ${text.length - maxChars} chars for display …]"
        val keep = maxChars - marker.length
        return text.take(keep) + marker to true
    }
}

object ShellEscape {
    val CONTAINER_ID_PATTERN = Regex("^[a-f0-9]{12,64}$")

    fun singleQuote(value: String): String {
        return "'" + value.replace("'", "'\"'\"'") + "'"
    }

    fun validateContainerId(containerId: String): String {
        require(containerId.matches(CONTAINER_ID_PATTERN)) {
            "Invalid container id: $containerId"
        }
        return containerId
    }
}
