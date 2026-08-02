package dev.stackward.ui.logs

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.stackward.StackwardApplication
import dev.stackward.inference.DeviceCapability
import dev.stackward.inference.ModelVariant
import dev.stackward.logs.DockerContainer
import dev.stackward.logs.JournalPriority
import dev.stackward.logs.JournalQuery
import dev.stackward.logs.JournalSince
import dev.stackward.logs.LogDigest
import dev.stackward.logs.LogDigestWorker
import dev.stackward.logs.LogReadResult
import dev.stackward.onboarding.ServerProfile
import dev.stackward.permissions.ActionProposal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class LogTab {
    JOURNAL,
    DOCKER,
    DIGEST,
}

data class LogsUiState(
    val profile: ServerProfile? = null,
    val selectedTab: LogTab = LogTab.JOURNAL,
    val since: JournalSince = JournalSince.ONE_HOUR,
    val priority: JournalPriority = JournalPriority.ERROR,
    val logOutput: String? = null,
    val truncated: Boolean = false,
    val containers: List<DockerContainer> = emptyList(),
    val selectedContainerId: String? = null,
    val savedDigest: LogDigest? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val lastFetchedAt: Long? = null,
    val deviceCapability: DeviceCapability? = null,
    val selectedModelVariant: ModelVariant = ModelVariant.E2B,
    val modelConfigured: Boolean = false,
    val modelFileName: String? = null,
    val isImportingModel: Boolean = false,
    val isSummarizing: Boolean = false,
    val aiSummary: String? = null,
    val actionProposals: List<ActionProposal> = emptyList(),
    val aiUnavailableReason: String? = null,
)

class LogsViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as StackwardApplication).container

    private val _uiState = MutableStateFlow(LogsUiState())
    val uiState: StateFlow<LogsUiState> = _uiState.asStateFlow()

    init {
        refreshModelStatus()
        reloadProfile()
    }

    fun refreshModelStatus() {
        val capability = container.deviceCapabilityChecker.assess()
        val modelPath = container.modelRepository.getConfiguredModelPath()
        val variant = container.modelRepository.getConfiguredVariant() ?: capability.recommendedVariant
        _uiState.update {
            it.copy(
                deviceCapability = capability,
                selectedModelVariant = variant,
                modelConfigured = modelPath != null,
                modelFileName = modelPath?.let { path -> File(path).name },
            )
        }
    }

    fun onModelVariantSelected(variant: ModelVariant) {
        _uiState.update { it.copy(selectedModelVariant = variant) }
    }

    fun importModel(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isImportingModel = true, error = null) }
            runCatching {
                withContext(Dispatchers.IO) {
                    container.modelImporter.importFromUri(
                        uri = uri,
                        variant = _uiState.value.selectedModelVariant,
                    )
                }
            }.onSuccess { path ->
                container.gemmaEngine.unload()
                refreshModelStatus()
                _uiState.update {
                    it.copy(
                        isImportingModel = false,
                        aiUnavailableReason = null,
                        error = null,
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isImportingModel = false,
                        error = error.message ?: "Failed to import model",
                    )
                }
            }
        }
    }

    fun summarizeCurrentLogs(userQuestion: String? = null) {
        val logs = _uiState.value.logOutput
        if (logs.isNullOrBlank()) {
            _uiState.update { it.copy(error = "Fetch logs before summarizing") }
            return
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isSummarizing = true,
                    error = null,
                    aiUnavailableReason = null,
                )
            }
            val result = container.logSummarizer.summarize(logs, userQuestion)
            _uiState.update {
                it.copy(
                    isSummarizing = false,
                    aiSummary = result.summary.takeIf { result.usedOnDeviceModel },
                    actionProposals = result.proposals,
                    aiUnavailableReason = result.unavailableReason,
                )
            }
        }
    }

    fun reloadProfile() {
        val profile = container.profileRepository.loadAll().firstOrNull()
        val savedDigest = container.logDigestStore.load()
        _uiState.update {
            it.copy(profile = profile, savedDigest = savedDigest)
        }
        if (profile != null) {
            LogDigestWorker.schedule(getApplication())
            refreshCurrentTab()
        }
    }

    fun selectTab(tab: LogTab) {
        _uiState.update { it.copy(selectedTab = tab, error = null) }
        refreshCurrentTab()
    }

    fun onSinceChange(since: JournalSince) {
        _uiState.update { it.copy(since = since) }
    }

    fun onPriorityChange(priority: JournalPriority) {
        _uiState.update { it.copy(priority = priority) }
    }

    fun onContainerSelected(containerId: String) {
        _uiState.update { it.copy(selectedContainerId = containerId) }
        fetchDockerLogs(containerId)
    }

    fun refreshCurrentTab() {
        when (_uiState.value.selectedTab) {
            LogTab.JOURNAL -> fetchJournal()
            LogTab.DOCKER -> fetchDockerTab()
            LogTab.DIGEST -> fetchDigest()
        }
    }

    fun fetchJournal() {
        val profile = _uiState.value.profile ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            runCatching {
                container.logReader.readJournal(
                    profile = profile,
                    query = JournalQuery(
                        since = _uiState.value.since,
                        priority = _uiState.value.priority,
                    ),
                )
            }.onSuccess { result ->
                applyLogResult(result)
            }.onFailure { error ->
                _uiState.update {
                    it.copy(isLoading = false, error = error.message ?: "Failed to read journal")
                }
            }
        }
    }

    fun fetchDockerTab() {
        val profile = _uiState.value.profile ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            runCatching {
                container.logReader.listContainers(profile)
            }.onSuccess { containers ->
                _uiState.update {
                    it.copy(
                        containers = containers,
                        isLoading = false,
                        error = if (containers.isEmpty()) {
                            "No Docker log directories visible for gemma-agent."
                        } else {
                            null
                        },
                    )
                }
                containers.firstOrNull()?.let { first ->
                    if (_uiState.value.selectedContainerId == null) {
                        onContainerSelected(first.id)
                    }
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(isLoading = false, error = error.message ?: "Failed to list containers")
                }
            }
        }
    }

    private fun fetchDockerLogs(containerId: String) {
        val profile = _uiState.value.profile ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            runCatching {
                container.logReader.readDockerLogs(profile, containerId)
            }.onSuccess { result ->
                applyLogResult(result)
            }.onFailure { error ->
                _uiState.update {
                    it.copy(isLoading = false, error = error.message ?: "Failed to read docker logs")
                }
            }
        }
    }

    fun fetchDigest() {
        val profile = _uiState.value.profile ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            runCatching {
                container.logReader.readDigest(profile)
            }.onSuccess { digest ->
                container.logDigestStore.save(digest)
                _uiState.update {
                    it.copy(
                        savedDigest = digest,
                        logOutput = digest.content,
                        truncated = digest.truncated,
                        isLoading = false,
                        lastFetchedAt = digest.generatedAt,
                        error = null,
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(isLoading = false, error = error.message ?: "Failed to build digest")
                }
            }
        }
    }

    private fun applyLogResult(result: LogReadResult) {
        _uiState.update {
            it.copy(
                logOutput = result.content.ifBlank { "(no log lines)" },
                truncated = result.truncated,
                isLoading = false,
                lastFetchedAt = System.currentTimeMillis(),
                error = null,
                aiSummary = null,
                actionProposals = emptyList(),
            )
        }
    }
}
