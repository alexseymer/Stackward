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
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.stackward.inference.ModelVariant
import dev.stackward.logs.JournalPriority
import dev.stackward.logs.JournalSince
import dev.stackward.permissions.ActionProposal
import dev.stackward.permissions.PermissionTier
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
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            viewModel.importModel(uri)
        }
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

                if (uiState.isLoading) {
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
                    AiSummaryCard(summary = summary, proposals = uiState.actionProposals)
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
    proposals: List<ActionProposal>,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Gemma summary", style = MaterialTheme.typography.titleSmall)
            Text(summary, style = MaterialTheme.typography.bodyMedium)
            if (proposals.isNotEmpty()) {
                Text("Proposed actions", style = MaterialTheme.typography.labelMedium)
                proposals.forEach { proposal ->
                    Text(
                        text = "[${proposal.tier.name}] ${proposal.action}: ${proposal.command}",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        text = proposal.reason,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (proposal.tier == PermissionTier.BOUNDARY_CHANGE) {
                        Text(
                            text = "Tier 3 — draft only, not auto-executed",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
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
