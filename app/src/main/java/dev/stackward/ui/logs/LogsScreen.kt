package dev.stackward.ui.logs

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
import androidx.compose.material.icons.filled.AutoAwesome
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
import androidx.fragment.app.FragmentActivity
import dev.stackward.inference.ModelVariant
import dev.stackward.logs.JournalPriority
import dev.stackward.logs.JournalSince
import dev.stackward.permissions.AuditEntry
import dev.stackward.permissions.PermissionDecision
import dev.stackward.ui.security.BiometricGate
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(
    viewModel: LogsViewModel,
    onOpenSettings: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsState()
    val profile = uiState.profile
    val context = LocalContext.current
    val biometricGate = remember(context) {
        BiometricGate(context as FragmentActivity)
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
                actions = {
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
            ) {
                Text("No server provisioned yet.")
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = onOpenSettings) {
                    Text("Open setup")
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
                    onImport = { importLauncher.launch(arrayOf("*/*")) },
                    onSummarize = viewModel::summarizeCurrentLogs,
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
                    LogTab.DIGEST -> Text(
                        text = "Hourly digest across journal + Docker (read-only, Tier 1).",
                        style = MaterialTheme.typography.bodySmall,
                    )
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
                    "No model imported — summarization disabled (no cloud fallback)."
                },
                style = MaterialTheme.typography.bodySmall,
            )
            if (uiState.isImportingModel) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onImport,
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
                        !uiState.logOutput.isNullOrBlank(),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null)
                    Text(
                        if (uiState.isSummarizing) "…" else "Summarize",
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
        }
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
