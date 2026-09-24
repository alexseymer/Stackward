package dev.stackward.ui.analyzer

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.stackward.StackwardApplication
import dev.stackward.inference.DeviceCapability
import dev.stackward.inference.ModelVariant
import dev.stackward.inference.SummarizationResult
import dev.stackward.logs.LogAnalyzerReport
import dev.stackward.logs.LogHeuristicAnalyzer
import dev.stackward.logs.SampleLogFixture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class LogAnalyzerUiState(
    val logText: String = "",
    val sourceLabel: String = "Pasted logs",
    val userQuestion: String = "",
    val heuristicFlags: List<LogHeuristicAnalyzer.Flag> = emptyList(),
    val deviceCapability: DeviceCapability? = null,
    val selectedModelVariant: ModelVariant = ModelVariant.E2B,
    val modelConfigured: Boolean = false,
    val modelFileName: String? = null,
    val isImportingModel: Boolean = false,
    val isSummarizing: Boolean = false,
    val aiSummary: String? = null,
    val aiUnavailableReason: String? = null,
    val lastSummarization: SummarizationResult? = null,
    val reportMarkdown: String? = null,
    val error: String? = null,
)

class LogAnalyzerViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as StackwardApplication).container

    private val _uiState = MutableStateFlow(LogAnalyzerUiState())
    val uiState: StateFlow<LogAnalyzerUiState> = _uiState.asStateFlow()

    init {
        refreshModelStatus()
    }

    fun setInitialLogs(logs: String, sourceLabel: String = "Imported from Logs") {
        if (logs.isBlank()) return
        applyLogText(logs, sourceLabel)
    }

    fun onLogTextChange(text: String) {
        applyLogText(text, sourceLabel = "Pasted logs")
    }

    fun onQuestionChange(question: String) {
        _uiState.update { it.copy(userQuestion = question) }
    }

    fun loadSample(fixture: SampleLogFixture) {
        applyLogText(fixture.content, sourceLabel = "Sample: ${fixture.title}")
    }

    fun importLogFile(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(error = null) }
            runCatching {
                withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                        input.bufferedReader().readText()
                    } ?: throw IllegalArgumentException("Could not read selected log file")
                }
            }.onSuccess { text ->
                val name = uri.lastPathSegment?.substringAfterLast('/') ?: "log file"
                applyLogText(text, sourceLabel = "File: $name")
            }.onFailure { error ->
                _uiState.update {
                    it.copy(error = error.message ?: "Failed to import log file")
                }
            }
        }
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
            }.onSuccess {
                container.gemmaEngine.unload()
                refreshModelStatus()
                _uiState.update {
                    it.copy(
                        isImportingModel = false,
                        aiUnavailableReason = null,
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

    fun runHeuristics() {
        val logs = _uiState.value.logText
        if (logs.isBlank()) {
            _uiState.update { it.copy(error = "Paste or load logs first") }
            return
        }
        val flags = LogHeuristicAnalyzer.analyze(logs)
        _uiState.update {
            it.copy(
                heuristicFlags = flags,
                error = null,
                reportMarkdown = buildReport(flags = flags, summarization = it.lastSummarization),
            )
        }
    }

    fun summarize() {
        val logs = _uiState.value.logText
        if (logs.isBlank()) {
            _uiState.update { it.copy(error = "Paste or load logs first") }
            return
        }

        val question = _uiState.value.userQuestion.takeIf { it.isNotBlank() }
        val flags = LogHeuristicAnalyzer.analyze(logs)

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isSummarizing = true,
                    error = null,
                    aiUnavailableReason = null,
                    heuristicFlags = flags,
                )
            }
            val result = container.logSummarizer.summarize(logs, question)
            _uiState.update { state ->
                state.copy(
                    isSummarizing = false,
                    heuristicFlags = flags,
                    aiSummary = result.summary.takeIf { result.usedOnDeviceModel },
                    aiUnavailableReason = result.unavailableReason,
                    lastSummarization = result,
                    reportMarkdown = buildReport(
                        flags = flags,
                        summarization = result,
                        userQuestion = question,
                        sourceLabel = state.sourceLabel,
                        logs = logs,
                    ),
                )
            }
        }
    }

    fun clearAnalysis() {
        _uiState.update {
            it.copy(
                aiSummary = null,
                aiUnavailableReason = null,
                lastSummarization = null,
                reportMarkdown = null,
                heuristicFlags = emptyList(),
            )
        }
    }

    private fun applyLogText(text: String, sourceLabel: String) {
        val flags = LogHeuristicAnalyzer.analyze(text)
        _uiState.update {
            it.copy(
                logText = text,
                sourceLabel = sourceLabel,
                heuristicFlags = flags,
                error = null,
                aiSummary = null,
                aiUnavailableReason = null,
                lastSummarization = null,
                reportMarkdown = buildReport(
                    flags = flags,
                    summarization = null,
                    sourceLabel = sourceLabel,
                    logs = text,
                ),
            )
        }
    }

    private fun buildReport(
        flags: List<LogHeuristicAnalyzer.Flag>,
        summarization: SummarizationResult?,
        userQuestion: String? = _uiState.value.userQuestion,
        sourceLabel: String = _uiState.value.sourceLabel,
        logs: String = _uiState.value.logText,
    ): String {
        return LogAnalyzerReport.buildMarkdown(
            sourceLabel = sourceLabel,
            logs = logs,
            heuristicFlags = flags,
            summarization = summarization,
            userQuestion = userQuestion,
        )
    }
}
