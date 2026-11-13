package dev.stackward.ui.dashboard

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.stackward.StackwardApplication
import dev.stackward.check.CheckResult
import dev.stackward.check.CheckRunResult
import dev.stackward.onboarding.ServerProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HostDetailUiState(
    val profile: ServerProfile? = null,
    val result: CheckResult? = null,
    val lastCheckedAt: Long? = null,
    val isChecking: Boolean = false,
    val error: String? = null,
)

/** Runs and shows a read-only check for one host. Nothing here changes the host (PRD D5). */
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
                error = profile?.let(::unresolvedFailure),
            )
        }
    }

    /** The last failure message, unless a later successful check superseded it. */
    private fun unresolvedFailure(profile: ServerProfile): String? {
        val store = container.checkResultStore
        val failedAt = store.getLastFailureAt(profile.id) ?: return null
        val checkedAt = store.getLastCheckedAt(profile.id)
        return if (checkedAt == null || failedAt >= checkedAt) store.getLastFailureMessage(profile.id) else null
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
                    container.checkResultStore.saveFailure(profile.id, outcome.message)
                    _uiState.update { it.copy(isChecking = false, error = outcome.message) }
                }
            }
        }
    }
}
