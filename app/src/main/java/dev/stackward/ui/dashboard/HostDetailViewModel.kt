package dev.stackward.ui.dashboard

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.stackward.StackwardApplication
import dev.stackward.check.CheckResult
import dev.stackward.check.CheckRunResult
import dev.stackward.check.CheckSuggestion
import dev.stackward.check.CheckSuggestionGate
import dev.stackward.check.SuggestionDecision
import dev.stackward.onboarding.ServerProfile
import dev.stackward.permissions.PermissionTier
import dev.stackward.permissions.recordAuditedExecution
import dev.stackward.ui.security.BiometricGate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A [SuggestionDecision.RequireConfirmation] awaiting biometric approval. */
data class PendingConfirmation(val suggestion: CheckSuggestion, val command: String)

/** A [SuggestionDecision.ManualOnly] being shown to the user (never executed). */
data class ManualStep(val suggestion: CheckSuggestion, val description: String)

data class HostDetailUiState(
    val profile: ServerProfile? = null,
    val result: CheckResult? = null,
    val lastCheckedAt: Long? = null,
    val isChecking: Boolean = false,
    val error: String? = null,
    val pendingConfirmation: PendingConfirmation? = null,
    val manualStep: ManualStep? = null,
    val executingSuggestionId: String? = null,
    val actionMessage: String? = null,
)

/**
 * Applies a check.sh suggestion via [CheckSuggestionGate]: safe runs immediately,
 * risky requires the same [BiometricGate] the app already uses for Tier 2 actions,
 * scary/unknown never executes — see PRD.md §5.1.
 */
class HostDetailViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as StackwardApplication).container

    private val _uiState = MutableStateFlow(HostDetailUiState())
    val uiState: StateFlow<HostDetailUiState> = _uiState.asStateFlow()

    fun load(profileId: String) {
        val profile = container.profileRepository.loadAll().find { it.id == profileId }
        _uiState.update {
            it.copy(
                profile = profile,
                result = profile?.let { p -> container.checkResultStore.load(p.id) },
                lastCheckedAt = profile?.let { p -> container.checkResultStore.getLastCheckedAt(p.id) },
                error = null,
            )
        }
    }

    fun checkNow() {
        val profile = _uiState.value.profile ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isChecking = true, error = null) }
            when (val outcome = container.checkScriptRunner.run(profile)) {
                is CheckRunResult.Success -> {
                    container.checkResultStore.save(profile.id, outcome.result)
                    _uiState.update {
                        it.copy(
                            isChecking = false,
                            result = outcome.result,
                            lastCheckedAt = container.checkResultStore.getLastCheckedAt(profile.id),
                        )
                    }
                }
                is CheckRunResult.Failure -> {
                    _uiState.update { it.copy(isChecking = false, error = outcome.message) }
                }
            }
        }
    }

    fun onApply(suggestion: CheckSuggestion) {
        when (val decision = CheckSuggestionGate.resolve(suggestion)) {
            is SuggestionDecision.AutoApprove ->
                executeAndAudit(suggestion, decision.command, PermissionTier.ROUTINE)
            is SuggestionDecision.RequireConfirmation ->
                _uiState.update {
                    it.copy(pendingConfirmation = PendingConfirmation(suggestion, decision.command))
                }
            is SuggestionDecision.ManualOnly ->
                _uiState.update { it.copy(manualStep = ManualStep(suggestion, decision.description)) }
        }
    }

    fun dismissPendingConfirmation() {
        _uiState.update { it.copy(pendingConfirmation = null) }
    }

    fun dismissManualStep() {
        _uiState.update { it.copy(manualStep = null) }
    }

    fun dismissActionMessage() {
        _uiState.update { it.copy(actionMessage = null) }
    }

    fun confirmPending(biometricGate: BiometricGate) {
        val pending = _uiState.value.pendingConfirmation ?: return
        viewModelScope.launch {
            val authed = biometricGate.authenticate(
                title = "Approve action",
                subtitle = pending.command,
            )
            if (!authed) {
                _uiState.update {
                    it.copy(pendingConfirmation = null, actionMessage = "Biometric confirmation cancelled")
                }
                return@launch
            }
            executeAndAudit(pending.suggestion, pending.command, PermissionTier.ONE_TIMER)
        }
    }

    private fun executeAndAudit(suggestion: CheckSuggestion, command: String, tier: PermissionTier) {
        val profile = _uiState.value.profile ?: return
        viewModelScope.launch {
            _uiState.update {
                it.copy(executingSuggestionId = suggestion.id, pendingConfirmation = null, actionMessage = null)
            }
            val outcome = container.auditLogRepository.recordAuditedExecution(
                tier = tier,
                command = command,
                reason = "check.sh suggestion \"${suggestion.id}\": ${suggestion.reason}",
            ) {
                container.ssh.execute(profile, command)
            }
            _uiState.update {
                it.copy(
                    executingSuggestionId = null,
                    actionMessage = outcome.fold(
                        onSuccess = { output -> "Ran: $command\n\n${output.take(MAX_MESSAGE_OUTPUT)}" },
                        onFailure = { error -> "Failed: ${error.message}" },
                    ),
                )
            }
        }
    }

    companion object {
        private const val MAX_MESSAGE_OUTPUT = 2_000
    }
}
