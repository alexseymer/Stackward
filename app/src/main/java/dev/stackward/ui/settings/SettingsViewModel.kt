package dev.stackward.ui.settings

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.stackward.StackwardApplication
import dev.stackward.security.Tier1SyncResult
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
    val tier1Rules: List<String> = emptyList(),
    val tier1ReviewDue: Boolean = false,
    val lastTier1ReviewLabel: String? = null,
    val auditEntryCount: Int = 0,
    val isBusy: Boolean = false,
    val statusMessage: String? = null,
    val error: String? = null,
    val tier1SyncResult: Tier1SyncResult? = null,
    val showPanicConfirm: Boolean = false,
    val showRotateConfirm: Boolean = false,
    val pendingAuditExport: String? = null,
)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as StackwardApplication).container

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        val profile = container.profileRepository.loadAll().firstOrNull()
        val dateFormat = DateFormat.getDateTimeInstance()
        val lastSuccess = profile?.let { container.connectionHealth.getLastSuccessAt(it.id) }
        val lastFailure = profile?.let { container.connectionHealth.getLastFailureAt(it.id) }
        val lastReview = container.securitySettings.getLastTier1ReviewAt()

        _uiState.update {
            it.copy(
                profileHost = profile?.host,
                profilePort = profile?.port,
                jumpHost = profile?.jumpHost,
                lastSuccessLabel = lastSuccess?.let { ts -> dateFormat.format(Date(ts)) },
                lastFailureLabel = lastFailure?.let { ts -> dateFormat.format(Date(ts)) },
                lastError = profile?.let { p -> container.connectionHealth.getLastError(p.id) },
                tier1Rules = container.tier1RulesRepository.loadRules(),
                tier1ReviewDue = container.securitySettings.isTier1ReviewDue(),
                lastTier1ReviewLabel = lastReview?.let { ts -> dateFormat.format(Date(ts)) },
                auditEntryCount = container.auditLogRepository.loadAll().size,
                error = null,
            )
        }
    }

    fun clearStatus() {
        _uiState.update { it.copy(statusMessage = null, error = null, tier1SyncResult = null) }
    }

    fun requestRotateKey() {
        _uiState.update { it.copy(showRotateConfirm = true, error = null) }
    }

    fun dismissRotateConfirm() {
        _uiState.update { it.copy(showRotateConfirm = false) }
    }

    fun rotateKey() {
        val profile = container.profileRepository.loadAll().firstOrNull()
        if (profile == null) {
            _uiState.update { it.copy(error = "No server provisioned") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, showRotateConfirm = false, error = null) }
            runCatching {
                container.keyRotationService.rotate(profile)
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
                        error = error.message ?: "Key rotation failed",
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
        val profile = container.profileRepository.loadAll().firstOrNull()
        if (profile == null) {
            _uiState.update { it.copy(error = "No server provisioned") }
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
                        error = error.message ?: "Panic revoke failed; local credentials wiped",
                    )
                }
                onComplete()
            }
        }
    }

    fun syncTier1Rules() {
        val profile = container.profileRepository.loadAll().firstOrNull()
        if (profile == null) {
            _uiState.update { it.copy(error = "No server provisioned") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, error = null) }
            runCatching {
                container.tier1RulesSyncer.syncFromServer(profile)
            }.onSuccess { result ->
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        tier1SyncResult = result,
                        statusMessage = "Tier 1 rules synced from server",
                    )
                }
                refresh()
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        error = error.message ?: "Failed to sync Tier 1 rules",
                    )
                }
            }
        }
    }

    fun markTier1Reviewed() {
        container.tier1RulesSyncer.markReviewedWithoutSync()
        refresh()
        _uiState.update { it.copy(statusMessage = "Tier 1 review marked complete") }
    }

    fun exportAuditLog(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, error = null) }
            runCatching {
                withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openOutputStream(uri)?.use { stream ->
                        stream.write(container.auditLogRepository.exportJson().toByteArray())
                    } ?: error("Could not open export destination")
                }
            }.onSuccess {
                _uiState.update {
                    it.copy(isBusy = false, statusMessage = "Audit log exported")
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        error = error.message ?: "Export failed",
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
            val authed = biometricGate.authenticate(
                title = "Rotate SSH key",
                subtitle = "Biometric required to rotate credentials",
            )
            if (!authed) {
                _uiState.update { it.copy(error = "Biometric authentication failed or cancelled") }
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
            val authed = biometricGate.authenticate(
                title = "Emergency revoke",
                subtitle = "This removes server access and wipes local credentials",
            )
            if (!authed) {
                _uiState.update { it.copy(error = "Biometric authentication failed or cancelled") }
                return@launch
            }
            panicRevoke {
                onPanicRevoked()
                onExportPrompt()
            }
        }
    }
}
