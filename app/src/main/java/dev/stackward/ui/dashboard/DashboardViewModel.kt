package dev.stackward.ui.dashboard

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.stackward.StackwardApplication
import dev.stackward.check.CheckRunResult
import dev.stackward.check.HostHealth
import dev.stackward.check.HostHealthMapper
import dev.stackward.check.IssueSeverity
import dev.stackward.check.coverageGaps
import dev.stackward.check.maxSeverity
import dev.stackward.onboarding.ServerProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HostSummary(
    val profile: ServerProfile,
    val lastCheckedAt: Long?,
    val issueCount: Int,
    val maxSeverity: IssueSeverity?,
    val blindSpots: Int = 0,
    val lastFailureAt: Long? = null,
    val lastFailureMessage: String? = null,
    val isChecking: Boolean = false,
    val error: String? = null,
) {
    val health: HostHealth
        get() = HostHealthMapper.health(lastCheckedAt, lastFailureAt, issueCount, System.currentTimeMillis())
}

data class DashboardUiState(
    val hosts: List<HostSummary> = emptyList(),
    val isCheckingAll: Boolean = false,
    val batchResult: BatchResult? = null,
)

/** Outcome of the last "Check all hosts". */
data class BatchResult(val total: Int, val failed: Int)

class DashboardViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as StackwardApplication).container

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _uiState.update {
            it.copy(hosts = container.profileRepository.loadAll().map(::summaryFor))
        }
    }

    fun checkNow(profileId: String) {
        viewModelScope.launch { runCheck(profileId) }
    }

    /**
     * Checks every host one after another and reports a per-host outcome. Sequential on
     * purpose: each host is its own SSH session (PRD Q1 is still open on whether one
     * biometric confirmation could cover the batch).
     */
    fun checkAll() {
        if (_uiState.value.isCheckingAll) return
        viewModelScope.launch {
            _uiState.update { it.copy(isCheckingAll = true, batchResult = null) }
            val ids = _uiState.value.hosts.map { it.profile.id }
            val failed = ids.filterNot { runCheck(it) }
            _uiState.update {
                it.copy(
                    isCheckingAll = false,
                    batchResult = BatchResult(total = ids.size, failed = failed.size),
                )
            }
        }
    }

    /** Runs one check and updates that host's row. Returns true when the check completed. */
    private suspend fun runCheck(profileId: String): Boolean {
        val profile = _uiState.value.hosts.find { it.profile.id == profileId }?.profile ?: return false
        updateHost(profileId) { it.copy(isChecking = true, error = null) }
        return when (val outcome = container.checkScriptRunner.run(profile)) {
            is CheckRunResult.Success -> {
                container.checkResultStore.save(profileId, outcome.result)
                updateHost(profileId) {
                    it.copy(
                        isChecking = false,
                        error = null,
                        lastCheckedAt = container.checkResultStore.getLastCheckedAt(profileId),
                        issueCount = outcome.result.issues.size,
                        maxSeverity = outcome.result.maxSeverity(),
                        blindSpots = outcome.result.coverageGaps().size,
                        lastFailureAt = null,
                        lastFailureMessage = null,
                    )
                }
                true
            }
            is CheckRunResult.Failure -> {
                container.checkResultStore.saveFailure(profileId, outcome.message)
                updateHost(profileId) {
                    it.copy(
                        isChecking = false,
                        error = outcome.message,
                        lastFailureAt = container.checkResultStore.getLastFailureAt(profileId),
                        lastFailureMessage = outcome.message,
                    )
                }
                false
            }
        }
    }

    private fun updateHost(profileId: String, transform: (HostSummary) -> HostSummary) {
        _uiState.update { state ->
            state.copy(
                hosts = state.hosts.map { host ->
                    if (host.profile.id == profileId) transform(host) else host
                },
            )
        }
    }

    private fun summaryFor(profile: ServerProfile): HostSummary {
        val result = container.checkResultStore.load(profile.id)
        return HostSummary(
            profile = profile,
            lastCheckedAt = container.checkResultStore.getLastCheckedAt(profile.id),
            issueCount = result?.issues?.size ?: 0,
            maxSeverity = result?.maxSeverity(),
            blindSpots = result?.coverageGaps()?.size ?: 0,
            lastFailureAt = container.checkResultStore.getLastFailureAt(profile.id),
            lastFailureMessage = container.checkResultStore.getLastFailureMessage(profile.id),
        )
    }
}
