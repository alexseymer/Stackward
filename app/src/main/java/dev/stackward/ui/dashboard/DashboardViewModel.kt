package dev.stackward.ui.dashboard

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.stackward.StackwardApplication
import dev.stackward.check.CheckRunResult
import dev.stackward.check.CheckWorker
import dev.stackward.check.IssueSeverity
import dev.stackward.check.PollingMode
import dev.stackward.check.maxSeverity
import dev.stackward.onboarding.ServerProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HostSummary(
    val profile: ServerProfile,
    val pollingMode: PollingMode,
    val lastCheckedAt: Long?,
    val issueCount: Int,
    val maxSeverity: IssueSeverity?,
    val isChecking: Boolean = false,
    val error: String? = null,
)

data class DashboardUiState(
    val hosts: List<HostSummary> = emptyList(),
)

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
        val profile = _uiState.value.hosts.find { it.profile.id == profileId }?.profile ?: return
        updateHost(profileId) { it.copy(isChecking = true, error = null) }
        viewModelScope.launch {
            when (val outcome = container.checkScriptRunner.run(profile)) {
                is CheckRunResult.Success -> {
                    container.checkResultStore.save(profileId, outcome.result)
                    updateHost(profileId) {
                        it.copy(
                            isChecking = false,
                            error = null,
                            lastCheckedAt = container.checkResultStore.getLastCheckedAt(profileId),
                            issueCount = outcome.result.issues.size,
                            maxSeverity = outcome.result.maxSeverity(),
                        )
                    }
                }
                is CheckRunResult.Failure -> {
                    updateHost(profileId) { it.copy(isChecking = false, error = outcome.message) }
                }
            }
        }
    }

    fun setPollingMode(profileId: String, mode: PollingMode) {
        container.hostPollingRepository.setMode(profileId, mode)
        if (mode == PollingMode.AUTO_4H) {
            CheckWorker.scheduleAuto4h(getApplication(), profileId)
        } else {
            CheckWorker.cancel(getApplication(), profileId)
        }
        updateHost(profileId) { it.copy(pollingMode = mode) }
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
            pollingMode = container.hostPollingRepository.getMode(profile.id),
            lastCheckedAt = container.checkResultStore.getLastCheckedAt(profile.id),
            issueCount = result?.issues?.size ?: 0,
            maxSeverity = result?.maxSeverity(),
        )
    }
}
