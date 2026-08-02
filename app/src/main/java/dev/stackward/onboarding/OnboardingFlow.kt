package dev.stackward.onboarding

import android.content.Context
import dev.stackward.connection.HostKeyPinStore
import dev.stackward.connection.SshConnectionConfig
import dev.stackward.connection.SshConnectionManager
import dev.stackward.connection.SshException
import dev.stackward.crypto.AgentKeyManager
import dev.stackward.proxmox.ProxmoxBootstrapParser
import java.util.UUID

/**
 * Onboarding flow: IP/port → review bootstrap script → provision → verify.
 *
 * When a jump host is set, the bastion is provisioned first as a pure relay
 * (agent key only), then the target is bootstrapped and verified through it.
 */
class OnboardingFlow(
    private val context: Context,
    private val keyManager: AgentKeyManager,
    private val ssh: SshConnectionManager,
    private val pinStore: HostKeyPinStore,
    private val profileRepository: ServerProfileRepository,
) {

    private val bootstrapRunner = BootstrapRunner(context)

    suspend fun start(
        host: String,
        port: Int,
        adminUsername: String,
        adminCredential: AdminCredential,
        hostType: HostType? = null,
        jumpHost: String? = null,
        jumpHostPort: Int = 22,
    ): BootstrapResult {
        require(adminCredential.type == CredentialType.SSH_PASSWORD) {
            "Only SSH password bootstrap is supported in Phase 0/1"
        }
        require(keyManager.hasKeypair()) {
            "Generate an SSH key before provisioning"
        }
        val normalizedJump = jumpHost?.trim()?.takeIf { it.isNotEmpty() }
        if (normalizedJump != null) {
            require(jumpHostPort in 1..65535) { "Jump host port must be 1–65535" }
            require(normalizedJump != host || jumpHostPort != port) {
                "Jump host must differ from the target host"
            }
        }

        val publicKey = keyManager.getPublicKeyOpenSSH()
        val linuxScript = bootstrapRunner.loadLinuxBootstrapScript()
        val password = adminCredential.value

        var jumpFingerprint: String? = null
        var bastionBootstrapOutput: String? = null

        if (normalizedJump != null) {
            val bastion = provisionBastionRelay(
                jumpHost = normalizedJump,
                jumpHostPort = jumpHostPort,
                adminUsername = adminUsername,
                password = password,
                publicKey = publicKey,
                linuxScript = linuxScript,
            )
            jumpFingerprint = bastion.fingerprint
            bastionBootstrapOutput = bastion.output
        }

        val resolvedHostType = hostType ?: detectHostType(
            host = host,
            port = port,
            adminUsername = adminUsername,
            adminCredential = adminCredential,
            jumpHost = normalizedJump,
            jumpHostPort = jumpHostPort,
            jumpHostKeyFingerprint = jumpFingerprint,
        )
        val previewScript = getBootstrapScript(resolvedHostType, publicKey)

        val targetAdminConfig = SshConnectionConfig(
            host = host,
            port = port,
            username = adminUsername,
            password = password,
        )

        val bootstrapResult = ssh.runScriptWithSudoPassword(
            config = targetAdminConfig,
            script = linuxScript,
            scriptArgument = publicKey,
            sudoPassword = password,
            jumpHost = normalizedJump,
            jumpHostPort = jumpHostPort,
            jumpHostKeyFingerprint = jumpFingerprint,
        )
        if (!bootstrapResult.isSuccess) {
            throw SshException(
                "Bootstrap failed: ${bootstrapResult.stderr.ifBlank { bootstrapResult.stdout }}",
            )
        }

        val fingerprint = pinStore.getPin(host, port)
            ?: throw SshException("Host key fingerprint was not pinned during bootstrap")

        val verifyResult = ssh.verifyAgentConnection(
            host = host,
            port = port,
            expectedFingerprint = fingerprint,
            jumpHost = normalizedJump,
            jumpHostPort = jumpHostPort,
            jumpHostKeyFingerprint = jumpFingerprint,
        )
        if (!verifyResult.isSuccess ||
            !verifyResult.stdout.contains(SshConnectionManager.AGENT_USERNAME)
        ) {
            throw SshException(
                "Agent verification failed: ${verifyResult.stderr.ifBlank { verifyResult.stdout }}",
            )
        }

        var proxmoxTokenId: String? = null
        var proxmoxTokenSecret: String? = null
        var combinedOutput = buildString {
            if (bastionBootstrapOutput != null) {
                append("=== Bastion (jump) bootstrap ===\n")
                append(bastionBootstrapOutput.trim())
                append("\n\n=== Target bootstrap ===\n")
            }
            append(bootstrapResult.stdout.trim())
        }

        if (resolvedHostType == HostType.PROXMOX) {
            val proxmoxScript = bootstrapRunner.loadProxmoxBootstrapScript()
            val proxmoxResult = ssh.runScriptWithSudoPassword(
                config = targetAdminConfig,
                script = proxmoxScript,
                scriptArgument = "",
                sudoPassword = password,
                jumpHost = normalizedJump,
                jumpHostPort = jumpHostPort,
                jumpHostKeyFingerprint = jumpFingerprint,
            )
            if (!proxmoxResult.isSuccess) {
                throw SshException(
                    "Proxmox bootstrap failed: " +
                        proxmoxResult.stderr.ifBlank { proxmoxResult.stdout },
                )
            }
            combinedOutput += "\n\n=== Proxmox bootstrap ===\n${proxmoxResult.stdout.trim()}"
            val credentials = ProxmoxBootstrapParser.parse(proxmoxResult.stdout)
                ?: throw SshException(
                    "Proxmox bootstrap succeeded but token output was not captured. " +
                        "Check pveum supports --output-format json.",
                )
            proxmoxTokenId = credentials.tokenId
            proxmoxTokenSecret = credentials.tokenSecret
        }

        val profile = ServerProfile(
            id = UUID.randomUUID().toString(),
            host = host,
            port = port,
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
            script = previewScript,
            proxmoxTokenId = proxmoxTokenId,
            proxmoxTokenSecret = proxmoxTokenSecret,
        )
    }

    /**
     * Installs the agent identity on the bastion so later connections can
     * authenticate with the Keystore key (pure relay — not a monitored host).
     */
    private suspend fun provisionBastionRelay(
        jumpHost: String,
        jumpHostPort: Int,
        adminUsername: String,
        password: String,
        publicKey: String,
        linuxScript: String,
    ): BastionProvisionResult {
        val bastionConfig = SshConnectionConfig(
            host = jumpHost,
            port = jumpHostPort,
            username = adminUsername,
            password = password,
        )
        val result = ssh.runScriptWithSudoPassword(
            config = bastionConfig,
            script = linuxScript,
            scriptArgument = publicKey,
            sudoPassword = password,
        )
        if (!result.isSuccess) {
            throw SshException(
                "Jump-host bootstrap failed: ${result.stderr.ifBlank { result.stdout }}",
            )
        }

        val fingerprint = pinStore.getPin(jumpHost, jumpHostPort)
            ?: throw SshException("Jump-host key fingerprint was not pinned during bootstrap")

        val verify = ssh.verifyAgentConnection(
            host = jumpHost,
            port = jumpHostPort,
            expectedFingerprint = fingerprint,
        )
        if (!verify.isSuccess ||
            !verify.stdout.contains(SshConnectionManager.AGENT_USERNAME)
        ) {
            throw SshException(
                "Jump-host agent verification failed: " +
                    verify.stderr.ifBlank { verify.stdout },
            )
        }

        return BastionProvisionResult(
            fingerprint = fingerprint,
            output = result.stdout.trim(),
        )
    }

    suspend fun detectHostType(
        host: String,
        port: Int,
        adminUsername: String,
        adminCredential: AdminCredential,
        jumpHost: String? = null,
        jumpHostPort: Int = 22,
        jumpHostKeyFingerprint: String? = null,
    ): HostType {
        require(adminCredential.type == CredentialType.SSH_PASSWORD) {
            "Host detection requires SSH password auth"
        }

        val probe = ssh.executeCommand(
            config = SshConnectionConfig(
                host = host,
                port = port,
                username = adminUsername,
                password = adminCredential.value,
            ),
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

    fun getBootstrapScript(hostType: HostType, publicKey: String): String {
        val linuxScript = bootstrapRunner.loadLinuxBootstrapScript()
        val script = when (hostType) {
            HostType.PROXMOX -> {
                val proxmoxScript = bootstrapRunner.loadProxmoxBootstrapScript()
                "$linuxScript\n\n# --- Proxmox API token (runs after Linux bootstrap) ---\n$proxmoxScript"
            }
            else -> linuxScript
        }
        return script.replace(
            "# Public key: (injected at runtime)",
            "# Public key: $publicKey",
        )
    }

    fun loadBootstrapScriptPreview(publicKey: String?, hostType: HostType? = null): String {
        val resolvedType = hostType ?: HostType.PLAIN_LINUX
        val script = if (publicKey.isNullOrBlank()) {
            when (resolvedType) {
                HostType.PROXMOX -> getBootstrapScript(resolvedType, "(generated at runtime)")
                else -> bootstrapRunner.loadLinuxBootstrapScript()
            }
        } else {
            getBootstrapScript(resolvedType, publicKey)
        }
        return if (publicKey.isNullOrBlank()) {
            script
        } else {
            "$script\n# Runtime SSH public key argument: $publicKey"
        }
    }

    private data class BastionProvisionResult(
        val fingerprint: String,
        val output: String,
    )
}
