package dev.stackward.connection

import dev.stackward.crypto.AgentKeyManager
import dev.stackward.onboarding.ServerProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Session
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * SSH connection manager with TOFU host key pinning.
 */
class SshConnectionManager(
    private val keyManager: AgentKeyManager,
    private val pinStore: HostKeyPinStore,
) {

    suspend fun execute(
        profile: ServerProfile,
        command: String,
        username: String = AGENT_USERNAME,
    ): String = withContext(Dispatchers.IO) {
        executeCommand(
            config = SshConnectionConfig(
                host = profile.host,
                port = profile.port,
                username = username,
                useAgentKey = true,
            ),
            command = command,
            expectedFingerprint = profile.hostKeyFingerprint,
        ).outputOrThrow()
    }

    suspend fun executeCommand(
        config: SshConnectionConfig,
        command: String,
        expectedFingerprint: String? = null,
    ): SshCommandResult = withContext(Dispatchers.IO) {
        connect(config, expectedFingerprint).use { client ->
            client.startSession().use { session ->
                session.exec(command).use { stream ->
                    readCommandResult(stream)
                }
            }
        }
    }

    suspend fun runScriptWithSudoPassword(
        config: SshConnectionConfig,
        script: String,
        scriptArgument: String,
        sudoPassword: String,
        expectedFingerprint: String? = null,
    ): SshCommandResult = withContext(Dispatchers.IO) {
        connect(config, expectedFingerprint).use { client ->
            client.startSession().use { session ->
                val remoteCommand = buildSudoScriptCommand(scriptArgument)
                session.exec(remoteCommand).use { stream ->
                    stream.outputStream.use { stdin ->
                        stdin.write("$sudoPassword\n".toByteArray(StandardCharsets.UTF_8))
                        stdin.write(script.toByteArray(StandardCharsets.UTF_8))
                        stdin.flush()
                    }
                    readCommandResult(stream)
                }
            }
        }
    }

    suspend fun verifyAgentConnection(
        host: String,
        port: Int,
        expectedFingerprint: String,
    ): SshCommandResult = executeCommand(
        config = SshConnectionConfig(
            host = host,
            port = port,
            username = AGENT_USERNAME,
            useAgentKey = true,
        ),
        command = "whoami && id -Gn",
        expectedFingerprint = expectedFingerprint,
    )

    fun verifyHostKey(profile: ServerProfile, actualFingerprint: String): Boolean {
        return profile.hostKeyFingerprint == actualFingerprint
    }

    private fun connect(
        config: SshConnectionConfig,
        expectedFingerprint: String?,
    ): SSHClient {
        val client = SSHClient()
        val verifier = TofuHostKeyVerifier(pinStore, config.host, config.port)
        client.addHostKeyVerifier(verifier)
        client.connectTimeout = CONNECT_TIMEOUT_MS.toInt()
        client.timeout = COMMAND_TIMEOUT_MS.toInt()

        client.connect(config.host, config.port)

        expectedFingerprint?.let { expected ->
            val actual = verifier.lastFingerprint
                ?: throw SshException("Host key fingerprint missing after connect")
            if (actual != expected) {
                client.disconnect()
                throw SshException("Host key mismatch during verification")
            }
        }

        when {
            config.useAgentKey -> {
                val keyPair = keyManager.getKeyPair()
                    ?: throw SshException("Agent SSH key not found on device")
                client.authPublickey(config.username, AgentSshKeyProvider(keyPair))
            }
            !config.password.isNullOrBlank() -> {
                client.authPassword(config.username, config.password)
            }
            else -> throw SshException("No SSH authentication method configured")
        }

        if (!client.isAuthenticated) {
            client.disconnect()
            throw SshException("SSH authentication failed for ${config.username}@${config.host}")
        }

        return client
    }

    private fun readCommandResult(stream: Session.Command): SshCommandResult {
        val stdout = stream.inputStream.bufferedReader().readText()
        val stderr = stream.errorStream.bufferedReader().readText()
        stream.join(COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        return SshCommandResult(
            stdout = stdout,
            stderr = stderr,
            exitStatus = stream.exitStatus ?: -1,
        )
    }

    private fun buildSudoScriptCommand(scriptArgument: String): String {
        val escapedArgument = shellSingleQuote(scriptArgument)
        return "sudo -S bash -s -- $escapedArgument"
    }

    private fun shellSingleQuote(value: String): String {
        return "'" + value.replace("'", "'\"'\"'") + "'"
    }

    companion object {
        const val AGENT_USERNAME = "gemma-agent"
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val COMMAND_TIMEOUT_MS = 120_000L
    }
}

private inline fun <T> SSHClient.use(block: (SSHClient) -> T): T {
    try {
        return block(this)
    } finally {
        if (isConnected) {
            disconnect()
        }
    }
}

private inline fun <T> Session.use(block: (Session) -> T): T {
    try {
        return block(this)
    } finally {
        close()
    }
}

private inline fun <T> Session.Command.use(block: (Session.Command) -> T): T {
    try {
        return block(this)
    } finally {
        close()
    }
}
