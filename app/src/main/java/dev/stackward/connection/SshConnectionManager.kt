package dev.stackward.connection

import dev.stackward.crypto.AgentKeyManager
import dev.stackward.onboarding.ServerProfile
import dev.stackward.security.SecuritySettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.DirectConnection
import net.schmizz.sshj.connection.channel.direct.Session
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * SSH connection manager with TOFU host key pinning, jump-host support, and retries.
 */
class SshConnectionManager(
    private val keyManager: AgentKeyManager,
    private val pinStore: HostKeyPinStore,
    private val securitySettings: SecuritySettingsRepository,
    private val connectionHealth: ConnectionHealthRepository,
) {

    suspend fun execute(
        profile: ServerProfile,
        command: String,
        username: String = AGENT_USERNAME,
        keyAlias: String? = null,
    ): String = executeWithRetry(
        profile = profile,
        command = command,
        username = username,
        keyAlias = keyAlias,
    )

    suspend fun executeWithRetry(
        profile: ServerProfile,
        command: String,
        username: String = AGENT_USERNAME,
        keyAlias: String? = null,
        maxAttempts: Int = DEFAULT_RETRY_ATTEMPTS,
    ): String = withContext(Dispatchers.IO) {
        var lastError: Exception? = null
        repeat(maxAttempts) { attempt ->
            try {
                val result = executeCommand(
                    profile = profile,
                    command = command,
                    username = username,
                    keyAlias = keyAlias,
                )
                connectionHealth.recordSuccess(profile.id)
                return@withContext result.outputOrThrow()
            } catch (error: Exception) {
                lastError = error
                connectionHealth.recordFailure(profile.id, error.message)
                if (attempt < maxAttempts - 1) {
                    delay(backoffDelayMs(attempt))
                }
            }
        }
        throw lastError ?: SshException("SSH command failed after $maxAttempts attempts")
    }

    suspend fun executeCommand(
        config: SshConnectionConfig,
        command: String,
        expectedFingerprint: String? = null,
        keyAlias: String? = null,
        jumpHost: String? = null,
        jumpHostPort: Int = 22,
        jumpHostKeyFingerprint: String? = null,
    ): SshCommandResult = withContext(Dispatchers.IO) {
        val resources = connect(
            config = config,
            expectedFingerprint = expectedFingerprint,
            keyAlias = keyAlias,
            jumpHost = jumpHost,
            jumpHostPort = jumpHostPort,
            jumpHostKeyFingerprint = jumpHostKeyFingerprint,
        )
        try {
            resources.target.startSession().use { session ->
                session.exec(command).use { stream ->
                    readCommandResult(stream)
                }
            }
        } finally {
            if (resources.target.isConnected) resources.target.disconnect()
            resources.tunnel?.close()
            if (resources.jump?.isConnected == true) resources.jump.disconnect()
        }
    }

    private suspend fun executeCommand(
        profile: ServerProfile,
        command: String,
        username: String,
        keyAlias: String?,
    ): SshCommandResult {
        return executeCommand(
            config = SshConnectionConfig(
                host = profile.host,
                port = profile.port,
                username = username,
                useAgentKey = true,
            ),
            command = command,
            expectedFingerprint = profile.hostKeyFingerprint,
            keyAlias = keyAlias,
            jumpHost = profile.jumpHost,
            jumpHostPort = profile.jumpHostPort,
            jumpHostKeyFingerprint = profile.jumpHostKeyFingerprint,
        )
    }

    suspend fun runScriptWithSudoPassword(
        config: SshConnectionConfig,
        script: String,
        scriptArgument: String,
        sudoPassword: String,
        expectedFingerprint: String? = null,
    ): SshCommandResult = withContext(Dispatchers.IO) {
        val resources = connect(
            config = config,
            expectedFingerprint = expectedFingerprint,
            keyAlias = null,
            jumpHost = null,
        )
        try {
            resources.target.startSession().use { session ->
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
        } finally {
            if (resources.target.isConnected) resources.target.disconnect()
            resources.tunnel?.close()
            if (resources.jump?.isConnected == true) resources.jump.disconnect()
        }
    }

    suspend fun verifyAgentConnection(
        host: String,
        port: Int,
        expectedFingerprint: String,
        keyAlias: String? = null,
        jumpHost: String? = null,
        jumpHostPort: Int = 22,
        jumpHostKeyFingerprint: String? = null,
    ): SshCommandResult = executeCommand(
        config = SshConnectionConfig(
            host = host,
            port = port,
            username = AGENT_USERNAME,
            useAgentKey = true,
        ),
        command = "whoami && id -Gn",
        expectedFingerprint = expectedFingerprint,
        keyAlias = keyAlias,
        jumpHost = jumpHost,
        jumpHostPort = jumpHostPort,
        jumpHostKeyFingerprint = jumpHostKeyFingerprint,
    )

    fun verifyHostKey(profile: ServerProfile, actualFingerprint: String): Boolean {
        return profile.hostKeyFingerprint == actualFingerprint
    }

    private fun connect(
        config: SshConnectionConfig,
        expectedFingerprint: String?,
        keyAlias: String?,
        jumpHost: String?,
        jumpHostPort: Int = 22,
        jumpHostKeyFingerprint: String? = null,
    ): ConnectedClients {
        val resolvedAlias = when {
            !config.useAgentKey -> null
            keyAlias != null -> keyAlias
            else -> securitySettings.getActiveKeyAlias()
        }

        return if (!jumpHost.isNullOrBlank()) {
            connectViaJumpHost(
                config = config,
                expectedFingerprint = expectedFingerprint,
                keyAlias = resolvedAlias,
                jumpHost = jumpHost,
                jumpHostPort = jumpHostPort,
                jumpHostKeyFingerprint = jumpHostKeyFingerprint,
            )
        } else {
            val target = connectDirect(
                config = config,
                expectedFingerprint = expectedFingerprint,
                keyAlias = resolvedAlias,
            )
            ConnectedClients(target = target, jump = null, tunnel = null)
        }
    }

    private fun connectViaJumpHost(
        config: SshConnectionConfig,
        expectedFingerprint: String?,
        keyAlias: String?,
        jumpHost: String,
        jumpHostPort: Int,
        jumpHostKeyFingerprint: String?,
    ): ConnectedClients {
        val jumpConfig = SshConnectionConfig(
            host = jumpHost,
            port = jumpHostPort,
            username = config.username,
            useAgentKey = config.useAgentKey,
            password = config.password,
        )
        val jumpClient = connectDirect(
            config = jumpConfig,
            expectedFingerprint = jumpHostKeyFingerprint,
            keyAlias = keyAlias,
        )
        val tunnel = jumpClient.newDirectConnection(config.host, config.port)
        val target = SSHClient()
        val verifier = TofuHostKeyVerifier(pinStore, config.host, config.port)
        target.addHostKeyVerifier(verifier)
        target.connectTimeout = CONNECT_TIMEOUT_MS.toInt()
        target.timeout = COMMAND_TIMEOUT_MS.toInt()
        target.connectVia(tunnel)

        expectedFingerprint?.let { expected ->
            val actual = verifier.lastFingerprint
                ?: throw SshException("Host key fingerprint missing after jump connect")
            if (actual != expected) {
                target.disconnect()
                jumpClient.disconnect()
                tunnel.close()
                throw SshException("Target host key mismatch during jump verification")
            }
        }

        authenticate(target, config, keyAlias)
        return ConnectedClients(target = target, jump = jumpClient, tunnel = tunnel)
    }

    private fun connectDirect(
        config: SshConnectionConfig,
        expectedFingerprint: String?,
        keyAlias: String?,
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

        authenticate(client, config, keyAlias)
        return client
    }

    private fun authenticate(
        client: SSHClient,
        config: SshConnectionConfig,
        keyAlias: String?,
    ) {
        when {
            config.useAgentKey -> {
                val alias = keyAlias ?: securitySettings.getActiveKeyAlias()
                val keyPair = keyManager.getKeyPair(alias)
                    ?: throw SshException("Agent SSH key not found on device ($alias)")
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

    private fun backoffDelayMs(attempt: Int): Long {
        return (1_000L shl attempt).coerceAtMost(30_000L)
    }

    private data class ConnectedClients(
        val target: SSHClient,
        val jump: SSHClient?,
        val tunnel: DirectConnection?,
    )

    companion object {
        const val AGENT_USERNAME = "gemma-agent"
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val COMMAND_TIMEOUT_MS = 120_000L
        private const val DEFAULT_RETRY_ATTEMPTS = 3
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
