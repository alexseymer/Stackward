package dev.stackward.check

import android.content.Context
import java.util.Base64

/**
 * The `check.sh` that ships inside the APK (`assets/check.sh`, kept identical to
 * `scripts/check.sh` by `scripts/verify_check_sh_sync.sh`) and the command that
 * installs it into the agent user's home. Installing touches only `~/.stackward/` —
 * no root, no sudo.
 */
class CheckScriptBundle(context: Context) {

    private val appContext = context.applicationContext

    val text: String by lazy {
        appContext.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
    }

    val version: Int get() = versionOf(text)

    fun installCommand(): String = installCommand(text)

    companion object {
        const val ASSET_NAME = "check.sh"
        private val VERSION_LINE = Regex("^# stackward-check-version:\\s*(\\d+)\\s*$", RegexOption.MULTILINE)

        /** The `# stackward-check-version: N` header, or 0 when absent. */
        fun versionOf(scriptText: String): Int =
            VERSION_LINE.find(scriptText)?.groupValues?.get(1)?.toIntOrNull() ?: 0

        /**
         * Writes the script atomically (temp file + mv) with owner-only permissions. The content
         * travels base64-encoded inside single quotes, so it is never interpreted by the shell.
         * Output ends with `STACKWARD_CHECK_INSTALLED=<version>` on success.
         */
        fun installCommand(scriptText: String): String {
            val encoded = Base64.getEncoder().encodeToString(scriptText.toByteArray(Charsets.UTF_8))
            return "umask 077 && mkdir -p ~/.stackward && chmod 700 ~/.stackward && " +
                "printf '%s' '$encoded' | base64 -d > ~/.stackward/check.sh.tmp && " +
                "chmod 700 ~/.stackward/check.sh.tmp && mv ~/.stackward/check.sh.tmp ~/.stackward/check.sh && " +
                "echo STACKWARD_CHECK_INSTALLED=${versionOf(scriptText)}"
        }
    }
}
