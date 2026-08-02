package dev.stackward.onboarding

import android.content.Context
import dev.stackward.connection.HostKeyPinStore
import dev.stackward.connection.SshConnectionConfig
import dev.stackward.connection.SshConnectionManager
import dev.stackward.connection.SshException
import dev.stackward.crypto.AgentKeyManager
import java.util.UUID

/**
 * Onboarding flow: IP/port → review bootstrap script → provision → verify.
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
    ): BootstrapResult {
        require(adminCredential.type == CredentialType.SSH_PASSWORD) {
            "Only SSH password bootstrap is supported in Phase 0/1"
        }
        require(keyManager.hasKeypair()) {
            "Generate an SSH key before provisioning"
        }

        val resolvedHostType = hostType ?: detectHostType(host, port, adminUsername, adminCredential)
        val publicKey = keyManager.getPublicKeyOpenSSH()
        val script = bootstrapRunner.loadLinuxBootstrapScript()

        val adminConfig = SshConnectionConfig(
            host = host,
            port = port,
            username = adminUsername,
            password = adminCredential.value,
        )

        val bootstrapResult = ssh.runScriptWithSudoPassword(
            config = adminConfig,
            script = script,
            scriptArgument = publicKey,
            sudoPassword = adminCredential.value,
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
        )
        if (!verifyResult.isSuccess ||
            !verifyResult.stdout.contains(SshConnectionManager.AGENT_USERNAME)
        ) {
            throw SshException(
                "Agent verification failed: ${verifyResult.stderr.ifBlank { verifyResult.stdout }}",
            )
        }

        val profile = ServerProfile(
            id = UUID.randomUUID().toString(),
            host = host,
            port = port,
            hostType = resolvedHostType,
            hostKeyFingerprint = fingerprint,
            provisionedAt = System.currentTimeMillis(),
        )
        profileRepository.save(profile)

        return BootstrapResult(
            profile = profile,
            bootstrapOutput = bootstrapResult.stdout.trim(),
            verificationOutput = verifyResult.stdout.trim(),
            publicKey = publicKey,
            script = script,
        )
    }

    suspend fun detectHostType(
        host: String,
        port: Int,
        adminUsername: String,
        adminCredential: AdminCredential,
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
        )

        return when (probe.outputOrThrow().trim()) {
            "proxmox" -> HostType.PROXMOX
            "docker" -> HostType.DOCKER
            else -> HostType.PLAIN_LINUX
        }
    }

    fun getBootstrapScript(hostType: HostType, publicKey: String): String {
        return when (hostType) {
            HostType.PROXMOX -> {
                bootstrapRunner.loadLinuxBootstrapScript() +
                    "\n\n# Next step (manual for now): scripts/bootstrap_proxmox.sh\n"
            }
            else -> bootstrapRunner.loadLinuxBootstrapScript()
        }.replace(
            "# Public key: (injected at runtime)",
            "# Public key: $publicKey",
        )
    }

    fun loadBootstrapScriptPreview(publicKey: String?): String {
        val script = bootstrapRunner.loadLinuxBootstrapScript()
        return if (publicKey.isNullOrBlank()) {
            script
        } else {
            "$script\n# Runtime argument: $publicKey"
        }
    }
}
