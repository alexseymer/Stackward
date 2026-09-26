package dev.stackward.ui.analyzer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.stackward.inference.ModelVariant
import dev.stackward.logs.SampleLogFixture
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogAnalyzerScreen(
    viewModel: LogAnalyzerViewModel,
    initialLogs: String? = null,
    initialSourceLabel: String = "Imported from Logs",
    onBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(initialLogs) {
        if (!initialLogs.isNullOrBlank()) {
            viewModel.setInitialLogs(initialLogs, initialSourceLabel)
        }
    }

    val modelImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) viewModel.importModel(uri)
    }

    val logImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) viewModel.importLogFile(uri)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Log Analyzer")
                        Text(
                            text = "Offline heuristics + on-device Gemma",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Paste logs, load a sample, or import a .log/.txt file. " +
                    "No SSH required — ideal for trying the on-device SLM.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SampleFixtureRow(onLoad = viewModel::loadSample)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { logImportLauncher.launch(arrayOf("text/*", "application/octet-stream", "*/*")) },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.UploadFile, contentDescription = null)
                    Text("Import logs", modifier = Modifier.padding(start = 4.dp))
                }
                OutlinedButton(
                    onClick = viewModel::runHeuristics,
                    enabled = uiState.logText.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Scan heuristics")
                }
            }

            OutlinedTextField(
                value = uiState.logText,
                onValueChange = viewModel::onLogTextChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp),
                label = { Text("Log text") },
                placeholder = { Text("Paste journal, Docker, or app logs here…") },
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )

            Text(
                text = "${uiState.sourceLabel} · ${uiState.logText.lines().size} lines",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (uiState.heuristicFlags.isNotEmpty()) {
                HeuristicFlagsCard(flags = uiState.heuristicFlags)
            }

            ModelCard(
                uiState = uiState,
                onVariantSelected = viewModel::onModelVariantSelected,
                onImportModel = { modelImportLauncher.launch(arrayOf("*/*")) },
                onQuestionChange = viewModel::onQuestionChange,
                onSummarize = viewModel::summarize,
            )

            if (uiState.isSummarizing) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator()
                }
            }

            uiState.aiUnavailableReason?.let { reason ->
                Text(
                    text = reason,
                    color = MaterialTheme.colorScheme.tertiary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            uiState.aiSummary?.let { summary ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Gemma summary", style = MaterialTheme.typography.titleSmall)
                        Text(summary, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            uiState.reportMarkdown?.let { report ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            copyToClipboard(context, report)
                            scope.launch {
                                snackbarHostState.showSnackbar("Report copied to clipboard")
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null)
                        Text("Copy report", modifier = Modifier.padding(start = 4.dp))
                    }
                    OutlinedButton(
                        onClick = viewModel::clearAnalysis,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Clear analysis")
                    }
                }
            }

            uiState.error?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun SampleFixtureRow(onLoad: (SampleLogFixture) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Samples", style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SampleLogFixture.entries.forEach { fixture ->
                FilterChip(
                    selected = false,
                    onClick = { onLoad(fixture) },
                    label = { Text(fixture.title) },
                )
            }
        }
    }
}

@Composable
private fun HeuristicFlagsCard(flags: List<dev.stackward.logs.LogHeuristicAnalyzer.Flag>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Heuristic scan", style = MaterialTheme.typography.titleSmall)
            flags.forEach { flag ->
                Text(
                    text = "${flag.label}: ${flag.count}",
                    style = MaterialTheme.typography.bodySmall,
                )
                flag.sampleLine?.let { sample ->
                    SelectionContainer {
                        Text(
                            text = sample,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelCard(
    uiState: LogAnalyzerUiState,
    onVariantSelected: (ModelVariant) -> Unit,
    onImportModel: () -> Unit,
    onQuestionChange: (String) -> Unit,
    onSummarize: () -> Unit,
) {
    val capability = uiState.deviceCapability
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("On-device model (Gemma)", style = MaterialTheme.typography.titleSmall)
            capability?.let {
                Text(
                    text = "Device RAM: ~${it.totalRamGb} GB · recommended ${it.recommendedVariant.displayName}",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModelVariant.entries.forEach { variant ->
                    FilterChip(
                        selected = uiState.selectedModelVariant == variant,
                        onClick = { onVariantSelected(variant) },
                        label = { Text(variant.name) },
                        enabled = when (variant) {
                            ModelVariant.E2B -> capability?.canRunE2B != false
                            ModelVariant.E4B -> capability?.canRunE4B == true
                        },
                    )
                }
            }
            Text(
                text = if (uiState.modelConfigured) {
                    "Loaded: ${uiState.modelFileName}"
                } else {
                    "Import a .task / .litertlm model to enable Gemma summarization."
                },
                style = MaterialTheme.typography.bodySmall,
            )
            if (uiState.isImportingModel) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            OutlinedTextField(
                value = uiState.userQuestion,
                onValueChange = onQuestionChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Question (optional)") },
                placeholder = { Text("What caused the 502 errors?") },
                minLines = 1,
                maxLines = 3,
                enabled = uiState.modelConfigured,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onImportModel,
                    enabled = !uiState.isImportingModel,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Upload, contentDescription = null)
                    Text("Import model", modifier = Modifier.padding(start = 4.dp))
                }
                Button(
                    onClick = onSummarize,
                    enabled = uiState.modelConfigured &&
                        !uiState.isSummarizing &&
                        uiState.logText.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null)
                    Text(
                        if (uiState.isSummarizing) "…" else "Analyze with Gemma",
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Stackward log report", text))
}
