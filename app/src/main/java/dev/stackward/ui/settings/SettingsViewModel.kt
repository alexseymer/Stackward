package dev.stackward.ui.settings

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.stackward.R
import dev.stackward.StackwardApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

data class SettingsUiState(
    val profileHost: String? = null,
    val profilePort: Int? = null,
    val jumpHost: String? = null,
    val lastSuccessLabel: String? = null,
    val lastFailureLabel: String? = null,
    val lastError: String? = null,
    val auditEntryCount: Int = 0,
    val isBusy: Boolean = false,
    val statusMessage: String? = null,
    val error: String? = null,
    val showPanicConfirm: Boolean = false,
    val showRotateConfirm: Boolean = false,
    val pendingAuditExport: String? = null,
)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as StackwardApplication).container

    private fun str(@androidx.annotation.StringRes id: Int, vararg args: Any): String =
        getApplication<Application>().getString(id, *args)

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private var selectedProfileId: String? = null

    fun refresh(profileId: String? = selectedProfileId) {
        val profiles = container.profileRepository.loadAll()
        val profile = profileId?.let { id -> profiles.firstOrNull { it.id == id } }
            ?: profiles.firstOrNull()
        selectedProfileId = profile?.id
        val dateFormat = DateFormat.getDateTimeInstance()
        val lastSuccess = profile?.let { container.connectionHealth.getLastSuccessAt(it.id) }
        val lastFailure = profile?.let { container.connectionHealth.getLastFailureAt(it.id) }

        _uiState.update {
            it.copy(
                profileHost = profile?.host,
                profilePort = profile?.port,
                jumpHost = profile?.jumpHost,
                lastSuccessLabel = lastSuccess?.let { ts -> dateFormat.format(Date(ts)) },
                lastFailureLabel = lastFailure?.let { ts -> dateFormat.format(Date(ts)) },
                lastError = profile?.let { p -> container.connectionHealth.getLastError(p.id) },
                auditEntryCount = container.auditLogRepository.loadAll().size,
                error = null,
            )
        }
    }

    private fun requireSelectedProfile() =
        selectedProfileId?.let { id ->
            container.profileRepository.loadAll().firstOrNull { it.id == id }
        } ?: container.profileRepository.loadAll().firstOrNull()

    fun clearStatus() {
        _uiState.update { it.copy(statusMessage = null, error = null) }
    }

    fun requestRotateKey() {
        _uiState.update { it.copy(showRotateConfirm = true, error = null) }
    }

    fun dismissRotateConfirm() {
        _uiState.update { it.copy(showRotateConfirm = false) }
    }

    fun rotateKey() {
        val profile = requireSelectedProfile()
        if (profile == null) {
            _uiState.update { it.copy(error = str(R.string.msg_no_server)) }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, showRotateConfirm = false, error = null) }
            // A password-sealed key (phones without a strong biometric) needs a password for its successor.
            val newKeyPassword = if (container.keyManager.needsPasswordForNewKey()) {
                container.appUnlock.askNewPassword() ?: run {
                    _uiState.update { it.copy(isBusy = false, error = str(R.string.unlock_cancelled)) }
                    return@launch
                }
            } else {
                null
            }
            runCatching {
                try {
                    container.keyRotationService.rotate(profile, newKeyPassword)
                } finally {
                    newKeyPassword?.fill('\u0000')
                }
            }.onSuccess { result ->
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        statusMessage = result.message,
                    )
                }
                refresh()
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        error = error.message ?: str(R.string.msg_rotation_failed),
                    )
                }
            }
        }
    }

    fun requestPanicRevoke() {
        _uiState.update { it.copy(showPanicConfirm = true, error = null) }
    }

    fun dismissPanicConfirm() {
        _uiState.update { it.copy(showPanicConfirm = false) }
    }

    fun panicRevoke(onComplete: () -> Unit) {
        val profile = requireSelectedProfile()
        if (profile == null) {
            _uiState.update { it.copy(error = str(R.string.msg_no_server)) }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, showPanicConfirm = false, error = null) }
            runCatching {
                container.panicRevokeService.revoke(profile)
            }.onSuccess { result ->
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        pendingAuditExport = result.auditExport,
                        statusMessage = result.serverMessage,
                    )
                }
                onComplete()
            }.onFailure { error ->
                container.panicRevokeService.wipeLocalState()
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        error = error.message ?: str(R.string.msg_panic_failed),
                    )
                }
                onComplete()
            }
        }
    }

    fun exportAuditLog(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, error = null) }
            runCatching {
                withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openOutputStream(uri)?.use { stream ->
                        stream.write(container.auditLogRepository.exportJson().toByteArray())
                    } ?: error(str(R.string.msg_export_no_destination))
                }
            }.onSuccess {
                _uiState.update {
                    it.copy(isBusy = false, statusMessage = str(R.string.msg_export_done))
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        error = error.message ?: str(R.string.msg_export_failed),
                    )
                }
            }
        }
    }

    fun consumePendingAuditExport(): String? {
        val export = _uiState.value.pendingAuditExport
        _uiState.update { it.copy(pendingAuditExport = null) }
        return export
    }

    fun getAuditExportContent(): String {
        return container.auditLogRepository.exportJson()
    }

    fun launchRotateWithBiometric(biometricGate: dev.stackward.ui.security.BiometricGate) {
        viewModelScope.launch {
            val authed = container.appUnlock.ensureUnlocked(biometricGate,
                title = str(R.string.bio_rotate_title),
                subtitle = str(R.string.bio_rotate_subtitle),
            )
            if (!authed) {
                _uiState.update { it.copy(error = str(R.string.msg_biometric_failed)) }
                return@launch
            }
            rotateKey()
        }
    }

    fun launchPanicWithBiometric(
        biometricGate: dev.stackward.ui.security.BiometricGate,
        onPanicRevoked: () -> Unit,
        onExportPrompt: () -> Unit,
    ) {
        viewModelScope.launch {
            val authed = container.appUnlock.ensureUnlocked(biometricGate,
                title = str(R.string.bio_panic_title),
                subtitle = str(R.string.bio_panic_subtitle),
            )
            if (!authed) {
                _uiState.update { it.copy(error = str(R.string.msg_biometric_failed)) }
                return@launch
            }
            panicRevoke {
                onPanicRevoked()
                onExportPrompt()
            }
        }
    }
}
