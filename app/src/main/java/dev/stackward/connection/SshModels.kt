package dev.stackward.connection

/**
 * Parameters for a single SSH connection attempt.
 *
 * Passwords and one-time private keys are for the current session only —
 * never write them to disk.
 */
data class SshConnectionConfig(
    val host: String,
    val port: Int = 22,
    val username: String,
    val password: String? = null,
    val privateKeyPem: String? = null,
    val privateKeyPassphrase: String? = null,
    val useAgentKey: Boolean = false,
)

/**
 * Result of a remote command execution.
 */
data class SshCommandResult(
    val stdout: String,
    val stderr: String,
    val exitStatus: Int,
) {
    val isSuccess: Boolean get() = exitStatus == 0

    fun outputOrThrow(): String {
        if (!isSuccess) {
            val detail = stderr.ifBlank { stdout }.ifBlank { "exit $exitStatus" }
            throw SshException("Remote command failed ($exitStatus): $detail")
        }
        return stdout.trim()
    }
}

class SshException(message: String, cause: Throwable? = null) : Exception(message, cause)
