package dev.stackward.ui.onboarding

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.stackward.StackwardApplication
import dev.stackward.onboarding.AdminCredential
import dev.stackward.onboarding.BootstrapResult
import dev.stackward.onboarding.CredentialType
import dev.stackward.onboarding.ServerProfile
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
    val publicKeyOpenSsh: String? = null,
    val usesHardwareKeystore: Boolean = false,
    val isGeneratingKey: Boolean = false,
    val isProvisioning: Boolean = false,
    val step: ProvisionStep = ProvisionStep.INPUT,
    val bootstrapScript: String? = null,
    val provisionedProfile: ServerProfile? = null,
    val bootstrapOutput: String? = null,
    val verificationOutput: String? = null,
    val message: String? = null,
    val error: String? = null,
) {
    val canPreviewScript: Boolean =
        host.isNotBlank() &&
            port.toIntOrNull() != null &&
            publicKeyOpenSsh != null &&
            adminCredential.isNotBlank() &&
            adminUsername.isNotBlank()

    val canStartBootstrap: Boolean = canPreviewScript && step == ProvisionStep.SCRIPT_PREVIEW
}

class OnboardingViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as StackwardApplication).container
    private val keyManager = container.keyManager
    private val pinStore = container.pinStore
    private val profileRepository = container.profileRepository
    private val ssh = container.ssh
    private val onboardingFlow = container.onboardingFlow

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
            _uiState.update {
                it.copy(
                    host = existing.host,
                    port = existing.port.toString(),
                    provisionedProfile = existing,
                    step = ProvisionStep.SUCCESS,
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

        val script = onboardingFlow.loadBootstrapScriptPreview(state.publicKeyOpenSsh)
        _uiState.update {
            it.copy(
                bootstrapScript = script,
                step = ProvisionStep.SCRIPT_PREVIEW,
                error = null,
            )
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
        _uiState.update {
            it.copy(
                isProvisioning = false,
                step = ProvisionStep.SUCCESS,
                provisionedProfile = result.profile,
                bootstrapOutput = result.bootstrapOutput,
                verificationOutput = result.verificationOutput,
                adminCredential = "",
                message = "Server provisioned and verified as ${result.profile.host}",
                error = null,
            )
        }
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }

    fun resetAfterRevoke() {
        _uiState.value = OnboardingUiState()
    }
}
