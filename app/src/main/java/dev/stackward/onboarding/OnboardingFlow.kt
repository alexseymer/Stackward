package dev.stackward.onboarding

import android.content.Context
import android.util.Log
import dev.stackward.connection.HostKeyPinStore
import dev.stackward.connection.SshConnectionConfig
import dev.stackward.connection.SshConnectionManager
import dev.stackward.connection.SshException
import dev.stackward.crypto.AgentKeyManager
import java.util.UUID

/**
 * Onboarding: chosen SSH user → one-time password login →
 * install authorized_keys → verify agent key → wipe password.
 *
 * Recommended default is a restricted agent account. Elevated identities
 * (root / passwordless sudo) are allowed when the UI has collected an
 * explicit acknowledgment. Passwords are never persisted.
 */
class OnboardingFlow(
    private val context: Context,
    private val keyManager: AgentKeyManager,
    private val ssh: SshConnectionManager,
    private val pinStore: HostKeyPinStore,
    private val profileRepository: ServerProfileRepository,
) {

    companion object {
        private const val TAG = "Stackward"

        /** Recommended prep command on the info screen (Debian/Ubuntu). */
        const val PREP_ADDUSER_COMMAND = "sudo adduser stackward-agent"
    }

    /**
     * Installs this device's public key into the chosen user's authorized_keys
     * (ssh-copy-id style), then verifies key-based login. Password is never saved.
     */
    suspend fun start(
        host: String,
        port: Int,
        agentUsername: String,
        login: BootstrapLogin,
        hostType: HostType? = null,
        jumpHost: String? = null,
        jumpHostPort: Int = 22,
        knockPorts: List<Int> = emptyList(),
    ): BootstrapResult {
        require(keyManager.hasKeypair()) {
            "Generate an SSH key before provisioning"
        }
        require(agentUsername.isNotBlank()) { "Agent username required" }

        val normalizedJump = jumpHost?.trim()?.takeIf { it.isNotEmpty() }
        if (normalizedJump != null) {
            require(jumpHostPort in 1..65535) { "Jump host port must be 1–65535" }
            require(normalizedJump != host || jumpHostPort != port) {
                "Jump host must differ from the target host"
            }
        }

        Log.i(
            TAG,
            "OnboardingFlow.start: $agentUsername@$host:$port jump=$normalizedJump knock=${knockPorts.size}",
        )
        if (knockPorts.isNotEmpty()) {
            PortKnocker.knock(host = host, ports = knockPorts)
        }

        val publicKey = keyManager.getPublicKeyOpenSSH()
        val loginConfig = login.toSshConfig(host, port, agentUsername)

        var jumpFingerprint: String? = null
        var bastionOutput: String? = null

        if (normalizedJump != null) {
            val bastion = installKeyOnHost(
                config = login.toSshConfig(normalizedJump, jumpHostPort, agentUsername),
                publicKey = publicKey,
                label = "jump host",
            )
            jumpFingerprint = bastion.fingerprint
            bastionOutput = bastion.output

            // Confirm jump accepts the agent key before tunneling to the target.
            val jumpVerify = ssh.verifyAgentConnection(
                host = normalizedJump,
                port = jumpHostPort,
                expectedFingerprint = jumpFingerprint,
                username = agentUsername,
            )
            if (!jumpVerify.isSuccess || !jumpVerify.stdout.contains(agentUsername)) {
                throw SshException(
                    "Jump-host key verification failed: " +
                        jumpVerify.stderr.ifBlank { jumpVerify.stdout },
                )
            }
        }

        val installResult = ssh.installAuthorizedKey(
            config = loginConfig,
            publicKeyOpenSsh = publicKey,
            jumpHost = normalizedJump,
            jumpHostPort = jumpHostPort,
            jumpHostKeyFingerprint = jumpFingerprint,
        )
        if (!installResult.isSuccess ||
            !installResult.stdout.contains("STACKWARD_KEY_INSTALLED=1")
        ) {
            throw SshException(
                "Authorized-keys install failed: " +
                    installResult.stderr.ifBlank { installResult.stdout },
            )
        }

        val fingerprint = pinStore.getPin(host, port)
            ?: throw SshException("Host key fingerprint was not pinned during setup")

        val verifyResult = ssh.verifyAgentConnection(
            host = host,
            port = port,
            expectedFingerprint = fingerprint,
            username = agentUsername,
            jumpHost = normalizedJump,
            jumpHostPort = jumpHostPort,
            jumpHostKeyFingerprint = jumpFingerprint,
        )
        if (!verifyResult.isSuccess || !verifyResult.stdout.contains(agentUsername)) {
            throw SshException(
                "Agent key verification failed: " +
                    verifyResult.stderr.ifBlank { verifyResult.stdout },
            )
        }

        val resolvedHostType = hostType ?: runCatching {
            detectHostType(
                host = host,
                port = port,
                agentUsername = agentUsername,
                // After wipe the caller discards password; detect with agent key.
                login = BootstrapLogin(method = BootstrapAuthMethod.PRIVATE_KEY, useAgentKey = true),
                jumpHost = normalizedJump,
                jumpHostPort = jumpHostPort,
                jumpHostKeyFingerprint = jumpFingerprint,
            )
        }.getOrDefault(HostType.PLAIN_LINUX)

        val combinedOutput = buildString {
            if (bastionOutput != null) {
                append("=== Jump host key install ===\n")
                append(bastionOutput.trim())
                append("\n\n=== Target key install ===\n")
            }
            append(installResult.stdout.trim())
            if (resolvedHostType == HostType.PROXMOX) {
                append(
                    "\n\n=== Note ===\n" +
                        "Proxmox detected. API tokens require an admin to run pveum out-of-band; " +
                        "the app does not create tokens itself.",
                )
            }
        }

        val profile = ServerProfile(
            id = UUID.randomUUID().toString(),
            host = host,
            port = port,
            username = agentUsername,
            hostType = resolvedHostType,
            hostKeyFingerprint = fingerprint,
            jumpHost = normalizedJump,
            jumpHostPort = jumpHostPort,
            jumpHostKeyFingerprint = jumpFingerprint,
            provisionedAt = System.currentTimeMillis(),
        )
        profileRepository.save(profile)

        return BootstrapResult(
            profile = profile,
            bootstrapOutput = combinedOutput,
            verificationOutput = verifyResult.stdout.trim(),
            publicKey = publicKey,
            script = keyInstallPreview(agentUsername, publicKey),
        )
    }

    /**
     * Verifies SSH login as the chosen user (does not install keys).
     * Detects elevated privilege for UI acknowledgment; does not refuse it.
     */
    suspend fun testLogin(
        host: String,
        port: Int,
        agentUsername: String,
        login: BootstrapLogin,
        jumpHost: String? = null,
        jumpHostPort: Int = 22,
        knockPorts: List<Int> = emptyList(),
    ): LoginProbeResult {
        require(agentUsername.isNotBlank()) { "Agent username required" }
        Log.i(TAG, "testLogin: $agentUsername@$host:$port jump=$jumpHost:$jumpHostPort")
        if (knockPorts.isNotEmpty()) {
            PortKnocker.knock(host = host, ports = knockPorts)
        }
        return try {
            val result = ssh.executeCommand(
                config = login.toSshConfig(host, port, agentUsername),
                command = "whoami && id -Gn && echo HOME=\$HOME && " +
                    "(command -v sudo >/dev/null 2>&1 && sudo -n true 2>/dev/null && echo sudo=yes || echo sudo=no)",
                jumpHost = jumpHost,
                jumpHostPort = jumpHostPort,
            )
            val output = result.outputOrThrow()
            LoginProbeResult.parse(output).also {
                Log.i(
                    TAG,
                    "testLogin: ok user=${it.username} elevated=${it.isElevated} ${output.take(120)}",
                )
            }
        } catch (error: Exception) {
            Log.e(TAG, "testLogin: failed", error)
            throw error
        }
    }

    suspend fun detectHostType(
        host: String,
        port: Int,
        agentUsername: String,
        login: BootstrapLogin,
        jumpHost: String? = null,
        jumpHostPort: Int = 22,
        jumpHostKeyFingerprint: String? = null,
    ): HostType {
        val probe = ssh.executeCommand(
            config = login.toSshConfig(host, port, agentUsername),
            command = "command -v pveversion >/dev/null 2>&1 && echo proxmox || " +
                "(command -v docker >/dev/null 2>&1 && echo docker || echo linux)",
            jumpHost = jumpHost,
            jumpHostPort = jumpHostPort,
            jumpHostKeyFingerprint = jumpHostKeyFingerprint,
        )

        return when (probe.outputOrThrow().trim()) {
            "proxmox" -> HostType.PROXMOX
            "docker" -> HostType.DOCKER
            else -> HostType.PLAIN_LINUX
        }
    }

    fun keyInstallPreview(agentUsername: String, publicKey: String?): String {
        val key = publicKey?.trim().orEmpty().ifBlank { "(device public key)" }
        return """
            # Recommended: restricted agent user (least privilege):
            #   $PREP_ADDUSER_COMMAND
            #
            # Identity power is your choice. Elevated accounts require an
            # explicit risk acknowledgment in the app before key install.
            #
            # This app logs in as $agentUsername with a one-time password and
            # installs the device public key into ~/.ssh/authorized_keys:
            mkdir -p ~/.ssh && chmod 700 ~/.ssh
            touch ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys
            # append (if missing):
            $key
            #
            # The password is wiped from the app afterwards and is never stored.
        """.trimIndent()
    }

    private suspend fun installKeyOnHost(
        config: SshConnectionConfig,
        publicKey: String,
        label: String,
    ): HostKeyInstallResult {
        val result = ssh.installAuthorizedKey(
            config = config,
            publicKeyOpenSsh = publicKey,
        )
        if (!result.isSuccess || !result.stdout.contains("STACKWARD_KEY_INSTALLED=1")) {
            throw SshException(
                "Authorized-keys install failed on $label: " +
                    result.stderr.ifBlank { result.stdout },
            )
        }
        val fingerprint = pinStore.getPin(config.host, config.port)
            ?: throw SshException("Host key fingerprint was not pinned on $label")
        return HostKeyInstallResult(fingerprint = fingerprint, output = result.stdout.trim())
    }

    private data class HostKeyInstallResult(
        val fingerprint: String,
        val output: String,
    )
}

/**
 * Result of the onboarding SSH login probe.
 * [isElevated] means root and/or passwordless sudo — UI must collect acknowledgment.
 */
data class LoginProbeResult(
    val rawOutput: String,
    val username: String,
    val isRoot: Boolean,
    val canPasswordlessSudo: Boolean,
) {
    val isElevated: Boolean get() = isRoot || canPasswordlessSudo

    val displayText: String
        get() = buildString {
            append(rawOutput.trim())
            if (isElevated) {
                append("\n\nSTACKWARD_PRIVILEGE=elevated")
                if (isRoot) append("\nSTACKWARD_PRIVILEGE_ROOT=1")
                if (canPasswordlessSudo) append("\nSTACKWARD_PRIVILEGE_SUDO=1")
            }
        }

    companion object {
        fun parse(output: String): LoginProbeResult {
            val lines = output.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val username = lines.firstOrNull().orEmpty()
            val canSudo = lines.any { it == "sudo=yes" }
            val isRoot = username == "root"
            return LoginProbeResult(
                rawOutput = output,
                username = username,
                isRoot = isRoot,
                canPasswordlessSudo = canSudo,
            )
        }
    }
}

private fun BootstrapLogin.toSshConfig(
    host: String,
    port: Int,
    username: String,
): SshConnectionConfig = when (method) {
    BootstrapAuthMethod.PASSWORD -> SshConnectionConfig(
        host = host,
        port = port,
        username = username,
        password = password,
    )
    BootstrapAuthMethod.PRIVATE_KEY -> SshConnectionConfig(
        host = host,
        port = port,
        username = username,
        privateKeyPem = privateKeyPem.takeUnless { useAgentKey },
        privateKeyPassphrase = privateKeyPassphrase.takeUnless { useAgentKey },
        useAgentKey = useAgentKey,
    )
}
