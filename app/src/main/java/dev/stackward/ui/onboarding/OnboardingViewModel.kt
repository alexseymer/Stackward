package dev.stackward.ui.onboarding

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.stackward.crypto.AgentKeyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class OnboardingUiState(
    val host: String = "",
    val port: String = "22",
    val adminCredential: String = "",
    val publicKeyOpenSsh: String? = null,
    val usesHardwareKeystore: Boolean = false,
    val isGeneratingKey: Boolean = false,
    val message: String? = null,
    val error: String? = null,
) {
    val canStartBootstrap: Boolean =
        host.isNotBlank() &&
            port.toIntOrNull() != null &&
            publicKeyOpenSsh != null &&
            adminCredential.isNotBlank()
}

class OnboardingViewModel(application: Application) : AndroidViewModel(application) {

    private val keyManager = AgentKeyManager(application.applicationContext)

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
    }

    fun onHostChange(value: String) {
        _uiState.update { it.copy(host = value.trim(), error = null) }
    }

    fun onPortChange(value: String) {
        _uiState.update { it.copy(port = value.filter { ch -> ch.isDigit() }, error = null) }
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

    fun startBootstrap() {
        _uiState.update {
            it.copy(message = "Server provisioning will be implemented in the next step")
        }
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }
}
