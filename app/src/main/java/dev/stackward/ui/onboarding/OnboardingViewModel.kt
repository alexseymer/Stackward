package dev.stackward.ui.onboarding

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.stackward.BuildConfig
import dev.stackward.StackwardApplication
import dev.stackward.crypto.SessionSecretVault
import dev.stackward.onboarding.BootstrapAuthMethod
import dev.stackward.onboarding.BootstrapLogin
import dev.stackward.onboarding.BootstrapResult
import dev.stackward.onboarding.HostType
import dev.stackward.onboarding.PortKnocker
import dev.stackward.onboarding.ServerProfile
import dev.stackward.ui.security.BiometricGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ProvisionStep {
    /** Recommended: create a restricted agent user on the host first. */
    PREP_INFO,
    INPUT,
    CONFIRM,
    PROVISIONING,
    SUCCESS,
}

/**
 * Onboarding UI state. Bootstrap secrets are never held here in plaintext —
 * only presence flags. Ciphertext lives in [SessionSecretVault].
 */
data class OnboardingUiState(
    val host: String = "",
    val port: String = "22",
    val agentUsername: String = ServerProfile.DEFAULT_AGENT_USERNAME,
    val authMethod: BootstrapAuthMethod = BootstrapAuthMethod.PASSWORD,
    val hasSshPassword: Boolean = false,
    val hasPrivateKeyPem: Boolean = false,
    val hasPrivateKeyPassphrase: Boolean = false,
    /** Bumped whenever sealed secrets are wiped so Compose local drafts clear. */
    val secretsEpoch: Int = 0,
    val knockSequence: String = "",
    val useJumpHost: Boolean = false,
    val jumpHost: String = "",
    val jumpHostPort: String = "22",
    val publicKeyOpenSsh: String? = null,
    val usesHardwareKeystore: Boolean = false,
    val isGeneratingKey: Boolean = false,
    val isTestingConnection: Boolean = false,
    val isProvisioning: Boolean = false,
    val connectionProbe: String? = null,
    /** True when login probe found root and/or passwordless sudo. */
    val elevatedPrivilegeDetected: Boolean = false,
    /** User acknowledged risk of connecting with an elevated identity. */
    val elevatedPrivilegeAcknowledged: Boolean = false,
    val step: ProvisionStep = ProvisionStep.PREP_INFO,
    val setupPreview: String? = null,
    val provisionedProfile: ServerProfile? = null,
    val bootstrapOutput: String? = null,
    val verificationOutput: String? = null,
    val proxmoxTokenPending: Boolean = false,
    val proxmoxTokenStored: Boolean = false,
    val message: String? = null,
    val error: String? = null,
) {
    val resolvedJumpHost: String?
        get() = if (useJumpHost) jumpHost.trim().takeIf { it.isNotEmpty() } else null

    val resolvedJumpHostPort: Int?
        get() = jumpHostPort.toIntOrNull()

    /** True when private-key login will use the generated agent key (no PEM paste). */
    val usingAgentKeyForLogin: Boolean =
        authMethod == BootstrapAuthMethod.PRIVATE_KEY &&
            !hasPrivateKeyPem &&
            publicKeyOpenSsh != null

    val hasLoginCredential: Boolean =
        when (authMethod) {
            BootstrapAuthMethod.PASSWORD -> hasSshPassword
            BootstrapAuthMethod.PRIVATE_KEY -> hasPrivateKeyPem || publicKeyOpenSsh != null
        }

    val canContinueFromPrep: Boolean = step == ProvisionStep.PREP_INFO

    val canContinueToConfirm: Boolean =
        host.isNotBlank() &&
            port.toIntOrNull() != null &&
            agentUsername.isNotBlank() &&
            publicKeyOpenSsh != null &&
            hasLoginCredential &&
            (!useJumpHost || (resolvedJumpHost != null && resolvedJumpHostPort != null))

    val canStartSetup: Boolean =
        canContinueToConfirm &&
            step == ProvisionStep.CONFIRM &&
            (!elevatedPrivilegeDetected || elevatedPrivilegeAcknowledged)
}

class OnboardingViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as StackwardApplication).container
    private val keyManager = container.keyManager
    private val profileRepository = container.profileRepository
    private val onboardingFlow = container.onboardingFlow
    private val proxmoxTokenStore = container.proxmoxTokenStore
    private val proxmoxApi = container.proxmoxApi
    private val secretVault = SessionSecretVault()

    companion object {
        private const val TAG = "Stackward"
    }

    private var pendingProxmoxTokenId: String? = null
    private var pendingProxmoxTokenSecret: String? = null

    private val _uiState = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    init {
        var base = OnboardingUiState()
        if (keyManager.hasKeypair()) {
            runCatching {
                val publicKey = keyManager.getPublicKeyOpenSSH()
                base = base.copy(
                    publicKeyOpenSsh = publicKey,
                    usesHardwareKeystore = keyManager.usesHardwareKeystore(),
                )
            }
        }
        _uiState.value = applyDevDefaults(base)
    }

    /** Clears wizard state so a new host can be provisioned. */
    fun startFresh() {
        wipeBootstrapSecrets()
        pendingProxmoxTokenId = null
        pendingProxmoxTokenSecret = null
        val publicKey = runCatching { keyManager.getPublicKeyOpenSSH() }.getOrNull()
        _uiState.value = applyDevDefaults(
            OnboardingUiState(
                publicKeyOpenSsh = publicKey,
                usesHardwareKeystore = keyManager.hasKeypair() && keyManager.usesHardwareKeystore(),
            ),
        )
    }

    /**
     * Re-seals debug password drafts into the vault after Compose clears local drafts
     * (e.g. secretsEpoch bump). No-op unless [BuildConfig.DEV_PREFILL] is enabled.
     */
    fun resealDevSecretDrafts() {
        if (!BuildConfig.DEBUG || !BuildConfig.DEV_PREFILL) return
        if (BuildConfig.DEV_SSH_PASSWORD.isNotEmpty()) {
            secretVault.put(SessionSecretVault.Slot.SSH_PASSWORD, BuildConfig.DEV_SSH_PASSWORD)
        }
        _uiState.update {
            it.copy(hasSshPassword = secretVault.has(SessionSecretVault.Slot.SSH_PASSWORD))
        }
    }

    /** Prefills onboarding fields from `local.properties` in debug builds only. */
    private fun applyDevDefaults(state: OnboardingUiState): OnboardingUiState {
        if (!BuildConfig.DEBUG || !BuildConfig.DEV_PREFILL) return state

        if (BuildConfig.DEV_SSH_PASSWORD.isNotEmpty()) {
            secretVault.put(SessionSecretVault.Slot.SSH_PASSWORD, BuildConfig.DEV_SSH_PASSWORD)
        }

        return state.copy(
            host = BuildConfig.DEV_HOST.ifBlank { state.host },
            port = BuildConfig.DEV_PORT.ifBlank { state.port },
            agentUsername = BuildConfig.DEV_USERNAME.ifBlank { state.agentUsername },
            knockSequence = BuildConfig.DEV_KNOCK_SEQUENCE,
            useJumpHost = BuildConfig.DEV_USE_JUMP_HOST,
            jumpHost = BuildConfig.DEV_JUMP_HOST,
            jumpHostPort = BuildConfig.DEV_JUMP_PORT.ifBlank { state.jumpHostPort },
            hasSshPassword = secretVault.has(SessionSecretVault.Slot.SSH_PASSWORD),
        )
    }

    fun acknowledgePrepInfo() {
        _uiState.update {
            it.copy(step = ProvisionStep.INPUT, error = null, message = null)
        }
    }

    fun onHostChange(value: String) {
        _uiState.update { it.copy(host = value.trim(), error = null) }
    }

    fun onPortChange(value: String) {
        _uiState.update { it.copy(port = value.filter { ch -> ch.isDigit() }, error = null) }
    }

    fun onAgentUsernameChange(value: String) {
        _uiState.update {
            it.copy(
                agentUsername = value.trim(),
                elevatedPrivilegeDetected = false,
                elevatedPrivilegeAcknowledged = false,
                error = null,
            )
        }
    }

    fun onElevatedPrivilegeAcknowledgedChange(acknowledged: Boolean) {
        _uiState.update { it.copy(elevatedPrivilegeAcknowledged = acknowledged, error = null) }
    }

    fun onAuthMethodChange(method: BootstrapAuthMethod) {
        when (method) {
            BootstrapAuthMethod.PASSWORD -> {
                secretVault.clear(SessionSecretVault.Slot.PRIVATE_KEY_PEM)
                secretVault.clear(SessionSecretVault.Slot.PRIVATE_KEY_PASSPHRASE)
            }
            BootstrapAuthMethod.PRIVATE_KEY -> {
                secretVault.clear(SessionSecretVault.Slot.SSH_PASSWORD)
            }
        }
        _uiState.update {
            it.copy(
                authMethod = method,
                hasSshPassword = secretVault.has(SessionSecretVault.Slot.SSH_PASSWORD),
                hasPrivateKeyPem = secretVault.has(SessionSecretVault.Slot.PRIVATE_KEY_PEM),
                hasPrivateKeyPassphrase = secretVault.has(SessionSecretVault.Slot.PRIVATE_KEY_PASSPHRASE),
                secretsEpoch = it.secretsEpoch + 1,
                error = null,
            )
        }
    }

    fun onSshPasswordChange(value: String) {
        secretVault.put(SessionSecretVault.Slot.SSH_PASSWORD, value)
        _uiState.update {
            it.copy(
                hasSshPassword = secretVault.has(SessionSecretVault.Slot.SSH_PASSWORD),
                error = null,
            )
        }
    }

    fun onPrivateKeyPemChange(value: String) {
        secretVault.put(SessionSecretVault.Slot.PRIVATE_KEY_PEM, value)
        _uiState.update {
            it.copy(
                hasPrivateKeyPem = secretVault.has(SessionSecretVault.Slot.PRIVATE_KEY_PEM),
                error = null,
            )
        }
    }

    fun onPrivateKeyPassphraseChange(value: String) {
        secretVault.put(SessionSecretVault.Slot.PRIVATE_KEY_PASSPHRASE, value)
        _uiState.update {
            it.copy(
                hasPrivateKeyPassphrase = secretVault.has(SessionSecretVault.Slot.PRIVATE_KEY_PASSPHRASE),
                error = null,
            )
        }
    }

    fun onKnockSequenceChange(value: String) {
        _uiState.update { it.copy(knockSequence = value, error = null) }
    }

    fun onUseJumpHostChange(enabled: Boolean) {
        _uiState.update { it.copy(useJumpHost = enabled, error = null) }
    }

    fun onJumpHostChange(value: String) {
        _uiState.update { it.copy(jumpHost = value.trim(), error = null) }
    }

    fun onJumpHostPortChange(value: String) {
        _uiState.update { it.copy(jumpHostPort = value.filter { ch -> ch.isDigit() }, error = null) }
    }

    fun generateSshKey() {
        viewModelScope.launch {
            _uiState.update { it.copy(isGeneratingKey = true, error = null) }
            Log.i(TAG, "generateSshKey: start")
            try {
                val publicKey = withContext(Dispatchers.IO) {
                    keyManager.generateKeypair()
                    keyManager.getPublicKeyOpenSSH()
                }
                Log.i(TAG, "generateSshKey: ok hardware=${keyManager.usesHardwareKeystore()}")
                val fillsPrivateKeyLogin =
                    _uiState.value.authMethod == BootstrapAuthMethod.PRIVATE_KEY &&
                        !secretVault.has(SessionSecretVault.Slot.PRIVATE_KEY_PEM)
                _uiState.update {
                    it.copy(
                        publicKeyOpenSsh = publicKey,
                        usesHardwareKeystore = keyManager.usesHardwareKeystore(),
                        isGeneratingKey = false,
                        message = if (fillsPrivateKeyLogin) {
                            "SSH key generated — private key login filled with agent key"
                        } else {
                            "SSH key generated successfully"
                        },
                    )
                }
            } catch (error: Exception) {
                Log.e(TAG, "generateSshKey: failed", error)
                _uiState.update {
                    it.copy(
                        isGeneratingKey = false,
                        error = error.message ?: "Failed to generate SSH key",
                    )
                }
            }
        }
    }

    fun continueToConfirm() {
        val state = _uiState.value
        if (!state.canContinueToConfirm) {
            Log.w(
                TAG,
                "continueToConfirm: blocked canContinue=false " +
                    "hostBlank=${state.host.isBlank()} port=${state.port} " +
                    "userBlank=${state.agentUsername.isBlank()} hasKey=${state.publicKeyOpenSsh != null} " +
                    "hasCred=${state.hasLoginCredential} jump=${state.useJumpHost}",
            )
            return
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isTestingConnection = true,
                    error = null,
                    connectionProbe = null,
                    elevatedPrivilegeDetected = false,
                    elevatedPrivilegeAcknowledged = false,
                )
            }
            Log.i(
                TAG,
                "continueToConfirm: probe ${state.agentUsername}@${state.host}:${state.port} " +
                    "auth=${state.authMethod} jump=${state.resolvedJumpHost}:${state.resolvedJumpHostPort}",
            )
            try {
                val login = openLogin()
                val knockPorts = PortKnocker.parseSequence(state.knockSequence)
                val probe = withContext(Dispatchers.IO) {
                    onboardingFlow.testLogin(
                        host = state.host,
                        port = state.port.toInt(),
                        agentUsername = state.agentUsername,
                        login = login,
                        jumpHost = state.resolvedJumpHost,
                        jumpHostPort = state.resolvedJumpHostPort ?: 22,
                        knockPorts = knockPorts,
                    )
                }
                val preview = onboardingFlow.keyInstallPreview(
                    agentUsername = state.agentUsername,
                    publicKey = state.publicKeyOpenSsh,
                )
                Log.i(
                    TAG,
                    "continueToConfirm: ok elevated=${probe.isElevated} probe=${probe.displayText.take(200)}",
                )
                _uiState.update {
                    it.copy(
                        isTestingConnection = false,
                        connectionProbe = probe.displayText,
                        elevatedPrivilegeDetected = probe.isElevated,
                        elevatedPrivilegeAcknowledged = false,
                        setupPreview = preview,
                        step = ProvisionStep.CONFIRM,
                        message = if (probe.isElevated) {
                            "Connected as ${state.agentUsername} — elevated privilege detected"
                        } else {
                            "Connected as ${state.agentUsername} — confirm key install"
                        },
                    )
                }
            } catch (error: Exception) {
                Log.e(TAG, "continueToConfirm: failed", error)
                _uiState.update {
                    it.copy(
                        isTestingConnection = false,
                        error = error.message ?: "Connection failed",
                    )
                }
            }
        }
    }

    fun backToPrep() {
        wipeBootstrapSecrets()
        _uiState.update {
            it.copy(
                step = ProvisionStep.PREP_INFO,
                setupPreview = null,
                connectionProbe = null,
                elevatedPrivilegeDetected = false,
                elevatedPrivilegeAcknowledged = false,
                error = null,
            )
        }
    }

    fun backToInput() {
        _uiState.update {
            it.copy(
                step = ProvisionStep.INPUT,
                setupPreview = null,
                elevatedPrivilegeDetected = false,
                elevatedPrivilegeAcknowledged = false,
                error = null,
            )
        }
    }

    fun startSetup() {
        val state = _uiState.value
        if (!state.canStartSetup) {
            Log.w(TAG, "startSetup: blocked canStart=false step=${state.step}")
            return
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isProvisioning = true,
                    step = ProvisionStep.PROVISIONING,
                    error = null,
                )
            }
            Log.i(TAG, "startSetup: ${state.host}:${state.port} jump=${state.resolvedJumpHost}")

            try {
                val login = openLogin()
                val knockPorts = PortKnocker.parseSequence(state.knockSequence)
                val result = withContext(Dispatchers.IO) {
                    onboardingFlow.start(
                        host = state.host,
                        port = state.port.toInt(),
                        agentUsername = state.agentUsername,
                        login = login,
                        jumpHost = state.resolvedJumpHost,
                        jumpHostPort = state.resolvedJumpHostPort ?: 22,
                        knockPorts = knockPorts,
                    )
                }
                Log.i(TAG, "startSetup: ok hostType=${result.profile.hostType}")
                wipeBootstrapSecrets()
                applyBootstrapSuccess(result)
            } catch (error: Exception) {
                Log.e(TAG, "startSetup: failed", error)
                wipeBootstrapSecrets()
                _uiState.update {
                    it.copy(
                        isProvisioning = false,
                        step = ProvisionStep.INPUT,
                        error = error.message ?: "Setup failed",
                    )
                }
            }
        }
    }

    private fun applyBootstrapSuccess(result: BootstrapResult) {
        pendingProxmoxTokenId = result.proxmoxTokenId
        pendingProxmoxTokenSecret = result.proxmoxTokenSecret
        val needsProxmoxToken = result.proxmoxTokenId != null && result.proxmoxTokenSecret != null

        _uiState.update {
            it.copy(
                isProvisioning = false,
                step = ProvisionStep.SUCCESS,
                provisionedProfile = result.profile,
                bootstrapOutput = result.bootstrapOutput,
                verificationOutput = result.verificationOutput,
                proxmoxTokenPending = needsProxmoxToken,
                proxmoxTokenStored = !needsProxmoxToken,
                message = if (result.profile.hostType == HostType.PROXMOX && !needsProxmoxToken) {
                    "Key installed — Proxmox API token must be configured by an admin out-of-band"
                } else {
                    "Key installed and verified as ${result.profile.username}@${result.profile.host}"
                },
                error = null,
            )
        }
    }

    fun storeProxmoxTokenWithBiometric(biometricGate: BiometricGate) {
        val tokenId = pendingProxmoxTokenId ?: return
        val tokenSecret = pendingProxmoxTokenSecret ?: return
        val profile = _uiState.value.provisionedProfile ?: return

        viewModelScope.launch {
            val authed = biometricGate.authenticate(
                title = "Store Proxmox API token",
                subtitle = "Biometric required to save the scoped API token",
            )
            if (!authed) {
                _uiState.update {
                    it.copy(error = "Biometric authentication failed or cancelled")
                }
                return@launch
            }

            try {
                withContext(Dispatchers.IO) {
                    proxmoxTokenStore.storeToken(tokenId, tokenSecret)
                    proxmoxApi.verifyConnection(profile)
                }
                pendingProxmoxTokenId = null
                pendingProxmoxTokenSecret = null
                _uiState.update {
                    it.copy(
                        proxmoxTokenPending = false,
                        proxmoxTokenStored = true,
                        message = "Proxmox API token stored and verified",
                        error = null,
                    )
                }
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(error = error.message ?: "Failed to store Proxmox token")
                }
            }
        }
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }

    fun resetAfterRevoke() {
        wipeBootstrapSecrets()
        pendingProxmoxTokenId = null
        pendingProxmoxTokenSecret = null
        _uiState.value = OnboardingUiState()
    }

    override fun onCleared() {
        wipeBootstrapSecrets()
        super.onCleared()
    }

    private fun openLogin(): BootstrapLogin {
        val state = _uiState.value
        return when (state.authMethod) {
            BootstrapAuthMethod.PASSWORD -> BootstrapLogin(
                method = BootstrapAuthMethod.PASSWORD,
                password = secretVault.get(SessionSecretVault.Slot.SSH_PASSWORD)
                    ?: throw IllegalStateException("SSH password missing"),
            )
            BootstrapAuthMethod.PRIVATE_KEY -> {
                val pem = secretVault.get(SessionSecretVault.Slot.PRIVATE_KEY_PEM)
                if (!pem.isNullOrBlank()) {
                    BootstrapLogin(
                        method = BootstrapAuthMethod.PRIVATE_KEY,
                        privateKeyPem = pem,
                        privateKeyPassphrase = secretVault.get(
                            SessionSecretVault.Slot.PRIVATE_KEY_PASSPHRASE,
                        ),
                    )
                } else if (state.publicKeyOpenSsh != null && keyManager.hasKeypair()) {
                    BootstrapLogin(
                        method = BootstrapAuthMethod.PRIVATE_KEY,
                        useAgentKey = true,
                    )
                } else {
                    throw IllegalStateException("SSH private key missing")
                }
            }
        }
    }

    private fun wipeBootstrapSecrets() {
        secretVault.clear()
        _uiState.update {
            it.copy(
                hasSshPassword = false,
                hasPrivateKeyPem = false,
                hasPrivateKeyPassphrase = false,
                secretsEpoch = it.secretsEpoch + 1,
            )
        }
    }
}
