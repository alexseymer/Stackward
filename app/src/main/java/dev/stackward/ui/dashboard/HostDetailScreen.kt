package dev.stackward.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.stackward.check.CheckIssue
import dev.stackward.check.CheckSuggestion
import dev.stackward.ui.security.BiometricGate
import dev.stackward.util.findFragmentActivity
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostDetailScreen(
    viewModel: HostDetailViewModel,
    onBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val biometricGate = remember(context) { BiometricGate(context.findFragmentActivity()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(uiState.profile?.host ?: "Host") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (uiState.isChecking) {
                        CircularProgressIndicator(modifier = Modifier.padding(end = 16.dp))
                    } else {
                        IconButton(onClick = viewModel::checkNow) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Check now")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            uiState.lastCheckedAt?.let {
                Text(
                    "Last checked: ${DateFormat.getDateTimeInstance().format(Date(it))}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            uiState.error?.let { error ->
                Text(
                    "Check failed: $error",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            val result = uiState.result
            if (result == null) {
                Text(
                    "No check results yet — tap refresh.",
                    modifier = Modifier.padding(top = 16.dp),
                )
            } else {
                Text(
                    "Issues (${result.issues.size})",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
                )
                if (result.issues.isEmpty()) {
                    Text("None — host looks clean.")
                } else {
                    result.issues.forEach { issue -> IssueRow(issue) }
                }

                Text(
                    "Suggestions (${result.suggestions.size})",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
                )
                if (result.suggestions.isEmpty()) {
                    Text("None right now.")
                } else {
                    result.suggestions.forEach { suggestion ->
                        SuggestionRow(
                            suggestion = suggestion,
                            isExecuting = uiState.executingSuggestionId == suggestion.id,
                            onApply = { viewModel.onApply(suggestion) },
                        )
                    }
                }
            }
        }
    }

    uiState.pendingConfirmation?.let { pending ->
        AlertDialog(
            onDismissRequest = viewModel::dismissPendingConfirmation,
            title = { Text("Confirm action") },
            text = {
                Column {
                    Text(pending.suggestion.reason)
                    Text(
                        pending.command,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            },
            confirmButton = {
                Button(onClick = { viewModel.confirmPending(biometricGate) }) {
                    Text("Approve with biometric")
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissPendingConfirmation) {
                    Text("Cancel")
                }
            },
        )
    }

    uiState.manualStep?.let { manual ->
        AlertDialog(
            onDismissRequest = viewModel::dismissManualStep,
            title = { Text("Manual step required") },
            text = {
                SelectionContainer {
                    Text(manual.description)
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::dismissManualStep) {
                    Text("OK")
                }
            },
        )
    }

    uiState.actionMessage?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::dismissActionMessage,
            title = { Text("Result") },
            text = {
                SelectionContainer {
                    Text(message)
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::dismissActionMessage) {
                    Text("OK")
                }
            },
        )
    }
}

@Composable
private fun IssueRow(issue: CheckIssue) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                "[${issue.severity.uppercase()}] ${issue.type}",
                style = MaterialTheme.typography.labelMedium,
            )
            Text(issue.message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun SuggestionRow(
    suggestion: CheckSuggestion,
    isExecuting: Boolean,
    onApply: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(suggestion.reason, style = MaterialTheme.typography.bodyMedium)
            Row(
                modifier = Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (isExecuting) {
                    CircularProgressIndicator(modifier = Modifier.padding(4.dp))
                } else {
                    OutlinedButton(onClick = onApply) {
                        Text("Apply")
                    }
                }
            }
        }
    }
}
