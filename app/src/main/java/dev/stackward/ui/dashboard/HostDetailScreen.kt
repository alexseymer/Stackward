package dev.stackward.ui.dashboard

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.stackward.check.CheckIssue
import dev.stackward.check.coverageGaps
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostDetailScreen(
    viewModel: HostDetailViewModel,
    onBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()

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
                OutlinedButton(onClick = viewModel::checkNow, enabled = !uiState.isChecking) {
                    Text("Retry")
                }
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
                    Text(if (result.coverageGaps().isEmpty()) "None — host looks clean." else "None found in what could be read.")
                } else {
                    result.issues.forEach { issue -> IssueRow(issue) }
                }

                val gaps = result.coverageGaps()
                if (gaps.isNotEmpty()) {
                    Text(
                        "Blind spots",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
                    )
                    gaps.forEach { gap ->
                        Text("• $gap", color = MaterialTheme.colorScheme.tertiary)
                    }
                    Text(
                        "These parts of the host were not checked, so \"no issues\" does not cover them.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun RowCard(content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(modifier = Modifier.padding(12.dp), content = content)
    }
}

@Composable
private fun IssueRow(issue: CheckIssue) {
    RowCard {
        Text(
            "[${issue.severity.uppercase()}] ${issue.type}",
            style = MaterialTheme.typography.labelMedium,
        )
        Text(issue.message, style = MaterialTheme.typography.bodyMedium)
        issue.advice?.let { advice ->
            SelectionContainer {
                Text(
                    advice,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}
