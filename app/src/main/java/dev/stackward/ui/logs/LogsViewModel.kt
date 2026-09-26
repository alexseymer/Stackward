package dev.stackward.ui.logs

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.stackward.StackwardApplication
import dev.stackward.inference.CatalogModel
import dev.stackward.inference.DeviceCapability
import dev.stackward.inference.ModelVariant
import dev.stackward.inference.StandardModelCatalog
import dev.stackward.logs.DockerContainer
import dev.stackward.logs.JournalPriority
import dev.stackward.logs.JournalQuery
import dev.stackward.logs.JournalSince
import dev.stackward.logs.LogDigest
import dev.stackward.logs.LogDigestWorker
import dev.stackward.logs.LogReadResult
import dev.stackward.onboarding.ServerProfile
import dev.stackward.permissions.ActionProposal
import dev.stackward.permissions.AuditEntry
import dev.stackward.permissions.PermissionDecision
import dev.stackward.ui.security.BiometricGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.min

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
    val isDownloadingModel: Boolean = false,
    val downloadProgress: Float = 0f,
    val downloadStatusLabel: String? = null,
    val catalogModels: List<CatalogModel> = StandardModelCatalog.models,
    val recommendedCatalogModelId: String? = null,
    val isSummarizing: Boolean = false,
    val aiSummary: String? = null,
    val actionProposals: List<ActionProposal> = emptyList(),
    val proposalDecisions: List<ProposalWithDecision> = emptyList(),
    val pendingConfirmation: ActionProposal? = null,
    val tier3Draft: String? = null,
    val isExecutingProposal: Boolean = false,
    val executionMessage: String? = null,
    val auditEntries: List<AuditEntry> = emptyList(),
    val aiUnavailableReason: String? = null,
    val summaryQuestion: String = "",
    val digestAnomalyFlags: List<String> = emptyList(),
)

class LogsViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as StackwardApplication).container

    private val _uiState = MutableStateFlow(LogsUiState())
    val uiState: StateFlow<LogsUiState> = _uiState.asStateFlow()

    private var selectedProfileId: String? = null
    private var modelDownloadJob: Job? = null

    init {
        refreshModelStatus()
        refreshAuditLog()
    }

    fun refreshModelStatus() {
        val capability = container.deviceCapabilityChecker.assess()
        val modelPath = container.modelRepository.getConfiguredModelPath()
        val variant = container.modelRepository.getConfiguredVariant() ?: capability.recommendedVariant
        val recommended = StandardModelCatalog.recommended(capability)
        _uiState.update {
            it.copy(
                deviceCapability = capability,
                selectedModelVariant = variant,
                modelConfigured = modelPath != null,
                modelFileName = modelPath?.let { path -> File(path).name },
                recommendedCatalogModelId = recommended.id,
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
            }.onSuccess {
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

    fun downloadCatalogModel(model: CatalogModel) {
        if (modelDownloadJob?.isActive == true) return

        modelDownloadJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isDownloadingModel = true,
                    downloadProgress = 0f,
                    downloadStatusLabel = "Starting ${model.displayName} (${model.approximateSizeLabel})…",
                    selectedModelVariant = model.variant,
                    error = null,
                )
            }
            try {
                withContext(Dispatchers.IO) {
                    container.modelImporter.downloadCatalogModel(model) { downloaded, total ->
                        if (!isActive) return@downloadCatalogModel
                        val progress = if (total > 0) {
                            min(1f, downloaded.toFloat() / total.toFloat())
                        } else {
                            0f
                        }
                        val label = if (total > 0) {
                            "Downloading ${model.displayName}: " +
                                "${formatBytes(downloaded)} / ${formatBytes(total)}"
                        } else {
                            "Downloading ${model.displayName}: ${formatBytes(downloaded)}"
                        }
                        _uiState.update {
                            it.copy(
                                downloadProgress = progress,
                                downloadStatusLabel = label,
                            )
                        }
                    }
                }
                container.gemmaEngine.unload()
                refreshModelStatus()
                _uiState.update {
                    it.copy(
                        isDownloadingModel = false,
                        downloadProgress = 1f,
                        downloadStatusLabel = null,
                        aiUnavailableReason = null,
                        error = null,
                    )
                }
            } catch (error: CancellationException) {
                _uiState.update {
                    it.copy(
                        isDownloadingModel = false,
                        downloadProgress = 0f,
                        downloadStatusLabel = null,
                        error = "Download cancelled",
                    )
                }
                throw error
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(
                        isDownloadingModel = false,
                        downloadProgress = 0f,
                        downloadStatusLabel = null,
                        error = error.message ?: "Failed to download model",
                    )
                }
            }
        }
    }

    fun cancelModelDownload() {
        modelDownloadJob?.cancel()
        modelDownloadJob = null
        _uiState.update {
            it.copy(
                isDownloadingModel = false,
                downloadProgress = 0f,
                downloadStatusLabel = null,
            )
        }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kib = bytes / 1024.0
        if (kib < 1024) return String.format("%.0f KB", kib)
        val mib = kib / 1024.0
        if (mib < 1024) return String.format("%.1f MB", mib)
        return String.format("%.2f GB", mib / 1024.0)
    }

    fun onSummaryQuestionChange(question: String) {
        _uiState.update { it.copy(summaryQuestion = question) }
    }

    fun summarizeCurrentLogs(userQuestion: String? = null) {
        val logs = _uiState.value.logOutput
        if (logs.isNullOrBlank()) {
            _uiState.update { it.copy(error = "Fetch logs before summarizing") }
            return
        }

        val question = userQuestion?.takeIf { it.isNotBlank() }
            ?: _uiState.value.summaryQuestion.takeIf { it.isNotBlank() }

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isSummarizing = true,
                    error = null,
                    aiUnavailableReason = null,
                )
            }
            val result = container.logSummarizer.summarize(logs, question)
            val decisions = result.proposals.map { proposal ->
                ProposalWithDecision(proposal, container.permissionExecutor.evaluate(proposal))
            }
            _uiState.update {
                it.copy(
                    isSummarizing = false,
                    aiSummary = result.summary.takeIf { result.usedOnDeviceModel },
                    actionProposals = result.proposals,
                    proposalDecisions = decisions,
                    aiUnavailableReason = result.unavailableReason,
                )
            }
        }
    }

    fun refreshAuditLog() {
        _uiState.update {
            it.copy(auditEntries = container.auditLogRepository.loadAll().takeLast(20).reversed())
        }
    }

    fun requestApproveProposal(proposal: ActionProposal) {
        when (val decision = container.permissionExecutor.evaluate(proposal)) {
            is PermissionDecision.RequireConfirmation -> {
                _uiState.update { it.copy(pendingConfirmation = proposal, tier3Draft = null) }
            }
            is PermissionDecision.Allow -> executeProposal(proposal, biometricGate = null)
            is PermissionDecision.DraftOnly -> {
                _uiState.update {
                    it.copy(tier3Draft = decision.suggestedDiff, pendingConfirmation = null)
                }
            }
            is PermissionDecision.Deny -> {
                _uiState.update { it.copy(error = decision.reason) }
            }
        }
    }

    fun dismissConfirmation() {
        _uiState.update { it.copy(pendingConfirmation = null) }
    }

    fun confirmPendingProposal(biometricGate: BiometricGate) {
        val proposal = _uiState.value.pendingConfirmation ?: return
        viewModelScope.launch {
            val authed = biometricGate.authenticate(
                title = "Approve Tier 2 action",
                subtitle = proposal.command,
            )
            if (!authed) {
                _uiState.update { it.copy(error = "Biometric authentication failed or cancelled") }
                return@launch
            }
            executeProposal(proposal, biometricGate)
        }
    }

    private fun executeProposal(proposal: ActionProposal, biometricGate: BiometricGate?) {
        val profile = _uiState.value.profile ?: return
        viewModelScope.launch {
            _uiState.update {
                it.copy(isExecutingProposal = true, pendingConfirmation = null, error = null)
            }
            runCatching {
                container.permissionExecutor.executeApproved(profile, proposal) { command ->
                    container.ssh.execute(profile, command)
                }
            }.onSuccess { result ->
                _uiState.update {
                    it.copy(
                        isExecutingProposal = false,
                        executionMessage = result.output,
                        error = if (result.success) null else result.output,
                    )
                }
                refreshAuditLog()
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isExecutingProposal = false,
                        error = error.message ?: "Execution failed",
                    )
                }
            }
        }
    }

    fun clearExecutionMessage() {
        _uiState.update { it.copy(executionMessage = null, tier3Draft = null) }
    }

    fun reloadProfile(profileId: String? = selectedProfileId) {
        selectedProfileId = profileId
        val profiles = container.profileRepository.loadAll()
        val profile = profileId?.let { id -> profiles.firstOrNull { it.id == id } }
        val savedDigest = container.logDigestStore.load()
        _uiState.update {
            it.copy(
                profile = profile,
                savedDigest = savedDigest,
                digestAnomalyFlags = savedDigest?.anomalyFlags.orEmpty(),
                error = null,
            )
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
                            "No Docker log directories visible for stackward-agent."
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
                container.logReader.readDockerContainerContext(profile, containerId)
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
                        digestAnomalyFlags = digest.anomalyFlags,
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
                proposalDecisions = emptyList(),
                pendingConfirmation = null,
                tier3Draft = null,
                executionMessage = null,
            )
        }
    }
}
