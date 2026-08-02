package dev.stackward.ui.onboarding

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.stackward.StackwardApplication
import dev.stackward.onboarding.AdminCredential
import dev.stackward.onboarding.BootstrapResult
import dev.stackward.onboarding.CredentialType
import dev.stackward.onboarding.HostType
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
    INPUT,
    SCRIPT_PREVIEW,
    PROVISIONING,
    SUCCESS,
}

data class OnboardingUiState(
    val host: String = "",
    val port: String = "22",
    val adminUsername: String = "root",
    val adminCredential: String = "",
    val useJumpHost: Boolean = false,
    val jumpHost: String = "",
    val jumpHostPort: String = "22",
    val publicKeyOpenSsh: String? = null,
    val usesHardwareKeystore: Boolean = false,
    val isGeneratingKey: Boolean = false,
    val isProvisioning: Boolean = false,
    val step: ProvisionStep = ProvisionStep.INPUT,
    val bootstrapScript: String? = null,
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

    val canPreviewScript: Boolean =
        host.isNotBlank() &&
            port.toIntOrNull() != null &&
            publicKeyOpenSsh != null &&
            adminCredential.isNotBlank() &&
            adminUsername.isNotBlank() &&
            (!useJumpHost || (resolvedJumpHost != null && resolvedJumpHostPort != null))

    val canStartBootstrap: Boolean = canPreviewScript && step == ProvisionStep.SCRIPT_PREVIEW
}

class OnboardingViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as StackwardApplication).container
    private val keyManager = container.keyManager
    private val pinStore = container.pinStore
    private val profileRepository = container.profileRepository
    private val ssh = container.ssh
    private val onboardingFlow = container.onboardingFlow
    private val proxmoxTokenStore = container.proxmoxTokenStore
    private val proxmoxApi = container.proxmoxApi

    private var pendingProxmoxTokenId: String? = null
    private var pendingProxmoxTokenSecret: String? = null

    private val _uiState = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    init {
        if (keyManager.hasKeypair()) {
            runCatching {
                val publicKey = keyManager.getPublicKeyOpenSSH()
                _uiState.update {
                    it.copy(
                        publicKeyOpenSsh = publicKey,
                        usesHardwareKeystore = keyManager.usesHardwareKeystore(),
                    )
                }
            }
        }

        val existing = profileRepository.loadAll().firstOrNull()
        if (existing != null) {
            val hasProxmoxToken = proxmoxTokenStore.getToken() != null
            _uiState.update {
                it.copy(
                    host = existing.host,
                    port = existing.port.toString(),
                    useJumpHost = !existing.jumpHost.isNullOrBlank(),
                    jumpHost = existing.jumpHost.orEmpty(),
                    jumpHostPort = existing.jumpHostPort.toString(),
                    provisionedProfile = existing,
                    step = ProvisionStep.SUCCESS,
                    proxmoxTokenPending = existing.hostType == HostType.PROXMOX && !hasProxmoxToken,
                    proxmoxTokenStored = existing.hostType != HostType.PROXMOX || hasProxmoxToken,
                )
            }
        }
    }

    fun onHostChange(value: String) {
        _uiState.update { it.copy(host = value.trim(), error = null) }
    }

    fun onPortChange(value: String) {
        _uiState.update { it.copy(port = value.filter { ch -> ch.isDigit() }, error = null) }
    }

    fun onAdminUsernameChange(value: String) {
        _uiState.update { it.copy(adminUsername = value.trim(), error = null) }
    }

    fun onAdminCredentialChange(value: String) {
        _uiState.update { it.copy(adminCredential = value, error = null) }
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
            try {
                val publicKey = withContext(Dispatchers.IO) {
                    keyManager.generateKeypair()
                    keyManager.getPublicKeyOpenSSH()
                }
                _uiState.update {
                    it.copy(
                        publicKeyOpenSsh = publicKey,
                        usesHardwareKeystore = keyManager.usesHardwareKeystore(),
                        isGeneratingKey = false,
                        message = "SSH key generated successfully",
                    )
                }
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(
                        isGeneratingKey = false,
                        error = error.message ?: "Failed to generate SSH key",
                    )
                }
            }
        }
    }

    fun showBootstrapScript() {
        val state = _uiState.value
        if (!state.canPreviewScript) return

        viewModelScope.launch {
            val jumpHost = state.resolvedJumpHost
            val jumpPort = state.resolvedJumpHostPort ?: 22
            val hostType = runCatching {
                withContext(Dispatchers.IO) {
                    onboardingFlow.detectHostType(
                        host = state.host,
                        port = state.port.toInt(),
                        adminUsername = state.adminUsername,
                        adminCredential = AdminCredential(
                            type = CredentialType.SSH_PASSWORD,
                            value = state.adminCredential,
                        ),
                        jumpHost = jumpHost,
                        jumpHostPort = jumpPort,
                    )
                }
            }.getOrDefault(HostType.PLAIN_LINUX)

            val script = onboardingFlow.loadBootstrapScriptPreview(
                publicKey = state.publicKeyOpenSsh,
                hostType = hostType,
            )
            val preview = if (jumpHost != null) {
                "# Jump host: $jumpHost:$jumpPort (bastion provisioned first as relay)\n" +
                    "# Target: ${state.host}:${state.port}\n" +
                    "# Same admin user/password is used on bastion and target during bootstrap.\n\n" +
                    script
            } else {
                script
            }
            _uiState.update {
                it.copy(
                    bootstrapScript = preview,
                    step = ProvisionStep.SCRIPT_PREVIEW,
                    error = null,
                )
            }
        }
    }

    fun backToInput() {
        _uiState.update {
            it.copy(
                step = ProvisionStep.INPUT,
                bootstrapScript = null,
                error = null,
            )
        }
    }

    fun startBootstrap() {
        val state = _uiState.value
        if (!state.canStartBootstrap) return

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isProvisioning = true,
                    step = ProvisionStep.PROVISIONING,
                    error = null,
                )
            }

            try {
                val result = withContext(Dispatchers.IO) {
                    onboardingFlow.start(
                        host = state.host,
                        port = state.port.toInt(),
                        adminUsername = state.adminUsername,
                        adminCredential = AdminCredential(
                            type = CredentialType.SSH_PASSWORD,
                            value = state.adminCredential,
                        ),
                        jumpHost = state.resolvedJumpHost,
                        jumpHostPort = state.resolvedJumpHostPort ?: 22,
                    )
                }

                applyBootstrapSuccess(result)
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(
                        isProvisioning = false,
                        step = ProvisionStep.SCRIPT_PREVIEW,
                        error = error.message ?: "Provisioning failed",
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
                adminCredential = "",
                proxmoxTokenPending = needsProxmoxToken,
                proxmoxTokenStored = !needsProxmoxToken,
                message = if (needsProxmoxToken) {
                    "Server provisioned — secure the Proxmox API token with biometrics"
                } else {
                    "Server provisioned and verified as ${result.profile.host}"
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
        pendingProxmoxTokenId = null
        pendingProxmoxTokenSecret = null
        _uiState.value = OnboardingUiState()
    }
}
