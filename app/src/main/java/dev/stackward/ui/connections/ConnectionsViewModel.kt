package dev.stackward.ui.connections

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import dev.stackward.StackwardApplication
import dev.stackward.onboarding.HostType
import dev.stackward.onboarding.ServerProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.DateFormat
import java.util.Date

data class ConnectionListItem(
    val id: String,
    val host: String,
    val port: Int,
    val hostType: HostType,
    val jumpHost: String?,
    val lastSuccessLabel: String?,
    val lastFailureLabel: String?,
    val lastError: String?,
    val isHealthy: Boolean,
)

data class ConnectionsUiState(
    val connections: List<ConnectionListItem> = emptyList(),
)

class ConnectionsViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as StackwardApplication).container

    private val _uiState = MutableStateFlow(ConnectionsUiState())
    val uiState: StateFlow<ConnectionsUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        val dateFormat = DateFormat.getDateTimeInstance()
        val items = container.profileRepository.loadAll().map { profile ->
            profile.toListItem(dateFormat)
        }
        _uiState.update { it.copy(connections = items) }
    }

    private fun ServerProfile.toListItem(dateFormat: DateFormat): ConnectionListItem {
        val lastSuccess = container.connectionHealth.getLastSuccessAt(id)
        val lastFailure = container.connectionHealth.getLastFailureAt(id)
        val lastError = container.connectionHealth.getLastError(id)
        val isHealthy = when {
            lastSuccess == null && lastFailure == null -> false
            lastFailure == null -> true
            lastSuccess == null -> false
            else -> lastSuccess >= lastFailure
        }
        return ConnectionListItem(
            id = id,
            host = host,
            port = port,
            hostType = hostType,
            jumpHost = jumpHost,
            lastSuccessLabel = lastSuccess?.let { dateFormat.format(Date(it)) },
            lastFailureLabel = lastFailure?.let { dateFormat.format(Date(it)) },
            lastError = lastError,
            isHealthy = isHealthy,
        )
    }
}
