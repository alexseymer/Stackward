package dev.stackward.ui.logs

import android.content.Intent
import android.net.Uri as AndroidUri
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
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.stackward.inference.CatalogModel
import dev.stackward.inference.ModelVariant
import dev.stackward.inference.StandardModelCatalog
import dev.stackward.logs.DigestAnomalyDetector
import dev.stackward.logs.JournalPriority
import dev.stackward.logs.JournalSince
import dev.stackward.permissions.AuditEntry
import dev.stackward.permissions.PermissionDecision
import dev.stackward.ui.security.BiometricGate
import dev.stackward.util.findFragmentActivity
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(
    viewModel: LogsViewModel,
    onBack: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenAnalyzer: (String?) -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsState()
    val profile = uiState.profile
    val context = LocalContext.current
    val biometricGate = remember(context) {
        BiometricGate(context.findFragmentActivity())
    }
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            viewModel.importModel(uri)
        }
    }

    uiState.pendingConfirmation?.let { proposal ->
        Tier2ConfirmationDialog(
            proposal = proposal,
            isExecuting = uiState.isExecutingProposal,
            onDismiss = viewModel::dismissConfirmation,
            onConfirm = { viewModel.confirmPendingProposal(biometricGate) },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Stackward Logs")
                        profile?.let {
                            Text(
                                text = "${it.host}:${it.port}",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Connections")
                    }
                },
                actions = {
                    IconButton(onClick = { onOpenAnalyzer(uiState.logOutput) }) {
                        Icon(Icons.Default.Analytics, contentDescription = "Log analyzer")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                    Button(onClick = viewModel::refreshCurrentTab) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Text("Refresh", modifier = Modifier.padding(start = 4.dp))
                    }
                },
            )
        },
    ) { padding ->
        if (profile == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("No connection selected.")
                Text(
                    text = "You can still try the offline Log Analyzer with sample logs — no SSH required.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = { onOpenAnalyzer(null) }) {
                    Icon(Icons.Default.Analytics, contentDescription = null)
                    Text("Open Log Analyzer", modifier = Modifier.padding(start = 4.dp))
                }
                Button(onClick = onBack) {
                    Text("Back to connections")
                }
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            TabRow(selectedTabIndex = uiState.selectedTab.ordinal) {
                LogTab.entries.forEach { tab ->
                    Tab(
                        selected = uiState.selectedTab == tab,
                        onClick = { viewModel.selectTab(tab) },
                        text = {
                            Text(
                                when (tab) {
                                    LogTab.JOURNAL -> "Journal"
                                    LogTab.DOCKER -> "Docker"
                                    LogTab.DIGEST -> "Digest"
                                },
                            )
                        },
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ModelStatusCard(
                    uiState = uiState,
                    onVariantSelected = viewModel::onModelVariantSelected,
                    onImport = { importLauncher.launch(arrayOf("*/*", "application/octet-stream")) },
                    onDownload = viewModel::downloadCatalogModel,
                    onCancelDownload = viewModel::cancelModelDownload,
                    onBrowseHuggingFace = { openUrl(context, StandardModelCatalog.HUGGING_FACE_BROWSE_URL) },
                    onBrowseModelPage = { url -> openUrl(context, url) },
                    onSummarize = viewModel::summarizeCurrentLogs,
                    onSummaryQuestionChange = viewModel::onSummaryQuestionChange,
                )

                when (uiState.selectedTab) {
                    LogTab.JOURNAL -> JournalControls(
                        since = uiState.since,
                        priority = uiState.priority,
                        onSinceChange = viewModel::onSinceChange,
                        onPriorityChange = viewModel::onPriorityChange,
                        onFetch = viewModel::fetchJournal,
                    )
                    LogTab.DOCKER -> DockerControls(
                        containers = uiState.containers,
                        selectedId = uiState.selectedContainerId,
                        onSelect = viewModel::onContainerSelected,
                    )
                    LogTab.DIGEST -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Hourly digest across journal + Docker (read-only, Tier 1).",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (uiState.digestAnomalyFlags.isNotEmpty()) {
                            Text(
                                text = "Flagged: " + uiState.digestAnomalyFlags.joinToString { flag ->
                                    DigestAnomalyDetector.label(flag)
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                    }
                }

                if (uiState.isLoading || uiState.isExecutingProposal) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }

                uiState.lastFetchedAt?.let { fetchedAt ->
                    Text(
                        text = "Last fetched: ${DateFormat.getDateTimeInstance().format(Date(fetchedAt))}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (uiState.truncated) {
                    Text(
                        text = "Output truncated for display.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }

                uiState.aiUnavailableReason?.let { reason ->
                    Text(
                        text = reason,
                        color = MaterialTheme.colorScheme.tertiary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                uiState.aiSummary?.let { summary ->
                    AiSummaryCard(
                        summary = summary,
                        proposalDecisions = uiState.proposalDecisions,
                        isExecuting = uiState.isExecutingProposal,
                        onApprove = viewModel::requestApproveProposal,
                    )
                }

                uiState.tier3Draft?.let { draft ->
                    Tier3DraftCard(
                        draft = draft,
                        onDismiss = viewModel::clearExecutionMessage,
                    )
                }

                uiState.executionMessage?.let { message ->
                    ExecutionResultCard(
                        message = message,
                        onDismiss = viewModel::clearExecutionMessage,
                    )
                }

                if (uiState.auditEntries.isNotEmpty()) {
                    AuditLogCard(entries = uiState.auditEntries)
                }

                uiState.error?.let { error ->
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                uiState.logOutput?.let { output ->
                    SelectionContainer {
                        Text(
                            text = output,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .verticalScroll(rememberScrollState()),
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                } ?: Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Tier2ConfirmationDialog(
    proposal: dev.stackward.permissions.ActionProposal,
    isExecuting: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Confirm Tier 2 action") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = proposal.reason,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = proposal.command,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = "This runs once via stackward-onetimer after biometric approval.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = !isExecuting,
            ) {
                Text(if (isExecuting) "Running…" else "Approve")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isExecuting) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun ModelStatusCard(
    uiState: LogsUiState,
    onVariantSelected: (ModelVariant) -> Unit,
    onImport: () -> Unit,
    onDownload: (CatalogModel) -> Unit,
    onCancelDownload: () -> Unit,
    onBrowseHuggingFace: () -> Unit,
    onBrowseModelPage: (String) -> Unit,
    onSummarize: () -> Unit,
    onSummaryQuestionChange: (String) -> Unit,
) {
    val capability = uiState.deviceCapability
    val busy = uiState.isImportingModel || uiState.isDownloadingModel
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

            if (!uiState.modelConfigured) {
                Text(
                    text = "No model on this phone yet. Download a standard LiteRT model below, " +
                        "or open Hugging Face to pick another .litertlm / .task file.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                Text(
                    text = "Loaded: ${uiState.modelFileName}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModelVariant.entries.forEach { variant ->
                    FilterChip(
                        selected = uiState.selectedModelVariant == variant,
                        onClick = { onVariantSelected(variant) },
                        label = { Text(variant.name) },
                        enabled = !busy && when (variant) {
                            ModelVariant.E2B -> capability?.canRunE2B != false
                            ModelVariant.E4B -> capability?.canRunE4B == true
                        },
                    )
                }
            }

            Text("Standard downloads", style = MaterialTheme.typography.labelMedium)
            uiState.catalogModels.forEach { model ->
                val recommended = model.id == uiState.recommendedCatalogModelId
                val ramOk = when (model.variant) {
                    ModelVariant.E2B -> capability?.canRunE2B != false
                    ModelVariant.E4B -> capability?.canRunE4B == true
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = buildString {
                            append(model.displayName)
                            append(" · ")
                            append(model.approximateSizeLabel)
                            if (recommended) append(" · recommended")
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        text = model.description,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { onDownload(model) },
                            enabled = !busy && ramOk,
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Default.CloudDownload, contentDescription = null)
                            Text(
                                if (recommended && !uiState.modelConfigured) {
                                    "Download"
                                } else {
                                    "Get ${model.variant.name}"
                                },
                                modifier = Modifier.padding(start = 4.dp),
                            )
                        }
                        TextButton(
                            onClick = { onBrowseModelPage(model.repoPageUrl) },
                            enabled = !busy,
                        ) {
                            Text("HF page")
                        }
                    }
                }
            }
            if (uiState.isDownloadingModel) {
                LinearProgressIndicator(
                    progress = { uiState.downloadProgress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = uiState.downloadStatusLabel ?: "Downloading…",
                    style = MaterialTheme.typography.labelSmall,
                )
                TextButton(onClick = onCancelDownload) {
                    Text("Cancel download")
                }
            } else if (uiState.isImportingModel) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text("Importing local file…", style = MaterialTheme.typography.labelSmall)
            }

            OutlinedTextField(
                value = uiState.summaryQuestion,
                onValueChange = onSummaryQuestionChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Question (optional)") },
                placeholder = { Text("Why is container X unhealthy?") },
                singleLine = false,
                minLines = 1,
                maxLines = 3,
                enabled = uiState.modelConfigured,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onBrowseHuggingFace,
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.OpenInBrowser, contentDescription = null)
                    Text("Hugging Face", modifier = Modifier.padding(start = 4.dp))
                }
                OutlinedButton(
                    onClick = onImport,
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Upload, contentDescription = null)
                    Text("Import file", modifier = Modifier.padding(start = 4.dp))
                }
            }

            Button(
                onClick = onSummarize,
                enabled = uiState.modelConfigured &&
                    !uiState.isSummarizing &&
                    !busy &&
                    !uiState.logOutput.isNullOrBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.AutoAwesome, contentDescription = null)
                Text(
                    if (uiState.isSummarizing) "Summarizing…" else "Summarize with Gemma",
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }
    }
}

private fun openUrl(context: android.content.Context, url: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, AndroidUri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

@Composable
private fun AiSummaryCard(
    summary: String,
    proposalDecisions: List<ProposalWithDecision>,
    isExecuting: Boolean,
    onApprove: (dev.stackward.permissions.ActionProposal) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Gemma summary", style = MaterialTheme.typography.titleSmall)
            Text(summary, style = MaterialTheme.typography.bodyMedium)
            if (proposalDecisions.isNotEmpty()) {
                Text("Proposed actions", style = MaterialTheme.typography.labelMedium)
                proposalDecisions.forEach { item ->
                    ProposalRow(
                        item = item,
                        isExecuting = isExecuting,
                        onApprove = onApprove,
                    )
                }
            }
        }
    }
}

@Composable
private fun ProposalRow(
    item: ProposalWithDecision,
    isExecuting: Boolean,
    onApprove: (dev.stackward.permissions.ActionProposal) -> Unit,
) {
    val proposal = item.proposal
    val decision = item.decision
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "[${proposal.tier.name}] ${proposal.action}",
                style = MaterialTheme.typography.labelMedium,
            )
            Text(
                text = proposal.command,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = proposal.reason,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when (decision) {
                is PermissionDecision.Allow -> {
                    Text(
                        text = "Tier 1 — allowed without confirmation",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Button(
                        onClick = { onApprove(proposal) },
                        enabled = !isExecuting,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Run")
                    }
                }
                is PermissionDecision.RequireConfirmation -> {
                    Text(
                        text = "Tier 2 — requires confirmation + biometric",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                    Button(
                        onClick = { onApprove(proposal) },
                        enabled = !isExecuting,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Review & approve")
                    }
                }
                is PermissionDecision.DraftOnly -> {
                    Text(
                        text = "Tier 3 — draft only, not auto-executed",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    OutlinedButton(
                        onClick = { onApprove(proposal) },
                        enabled = !isExecuting,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("View sudoers draft")
                    }
                }
                is PermissionDecision.Deny -> {
                    Text(
                        text = "Blocked: ${decision.reason}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun Tier3DraftCard(
    draft: String,
    onDismiss: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Tier 3 sudoers draft", style = MaterialTheme.typography.titleSmall)
            Text(
                text = "Apply manually on the server with visudo. Stackward will not execute this.",
                style = MaterialTheme.typography.bodySmall,
            )
            SelectionContainer {
                Text(
                    text = draft,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            TextButton(onClick = onDismiss) {
                Text("Dismiss")
            }
        }
    }
}

@Composable
private fun ExecutionResultCard(
    message: String,
    onDismiss: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Execution result", style = MaterialTheme.typography.titleSmall)
            SelectionContainer {
                Text(
                    text = message,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            TextButton(onClick = onDismiss) {
                Text("Dismiss")
            }
        }
    }
}

@Composable
private fun AuditLogCard(entries: List<AuditEntry>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Audit log (recent)", style = MaterialTheme.typography.titleSmall)
            entries.take(8).forEach { entry ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = buildString {
                            append(DateFormat.getDateTimeInstance().format(Date(entry.timestamp)))
                            append(" · ")
                            append(entry.tier.name)
                            append(if (entry.approved) " · approved" else " · denied")
                        },
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Text(
                        text = entry.command,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    entry.reason?.let { reason ->
                        Text(
                            text = reason,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    entry.output?.let { output ->
                        Text(
                            text = output.take(200).let { if (output.length > 200) "$it…" else it },
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun JournalControls(
    since: JournalSince,
    priority: JournalPriority,
    onSinceChange: (JournalSince) -> Unit,
    onPriorityChange: (JournalPriority) -> Unit,
    onFetch: () -> Unit,
) {
    Text("Journal filters", style = MaterialTheme.typography.labelMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        JournalSince.entries.forEach { option ->
            FilterChip(
                selected = since == option,
                onClick = { onSinceChange(option) },
                label = {
                    Text(
                        when (option) {
                            JournalSince.ONE_HOUR -> "1h"
                            JournalSince.SIX_HOURS -> "6h"
                            JournalSince.TWENTY_FOUR_HOURS -> "24h"
                        },
                    )
                },
            )
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        JournalPriority.entries.forEach { option ->
            FilterChip(
                selected = priority == option,
                onClick = { onPriorityChange(option) },
                label = { Text(option.name.lowercase().replaceFirstChar { it.uppercase() }) },
            )
        }
    }
    Button(onClick = onFetch, modifier = Modifier.fillMaxWidth()) {
        Text("Fetch journal")
    }
}

@Composable
private fun DockerControls(
    containers: List<dev.stackward.logs.DockerContainer>,
    selectedId: String?,
    onSelect: (String) -> Unit,
) {
    Text("Container logs (via file ACL)", style = MaterialTheme.typography.labelMedium)
    if (containers.isEmpty()) {
        Text("No containers found.", style = MaterialTheme.typography.bodySmall)
        return
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        containers.take(6).forEach { container ->
            FilterChip(
                selected = selectedId == container.id,
                onClick = { onSelect(container.id) },
                label = { Text(container.shortId) },
            )
        }
    }
}
