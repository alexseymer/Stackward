package dev.stackward.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.stackward.check.HostHealth
import dev.stackward.check.HostHealthMapper
import dev.stackward.check.IssueSeverity

private val StatusGreen = Color(0xFF2E7D32)
private val StatusAmber = Color(0xFFF9A825)
private val StatusOrange = Color(0xFFEF6C00)
private val StatusRed = Color(0xFFC62828)
private val StatusGray = Color(0xFF9E9E9E)

private fun statusColor(host: HostSummary): Color = when (host.health) {
    HostHealth.NEVER_CHECKED -> StatusGray
    HostHealth.UNREACHABLE -> StatusRed
    HostHealth.STALE -> StatusAmber
    HostHealth.CLEAN -> StatusGreen
    HostHealth.ISSUES -> when (host.maxSeverity) {
        IssueSeverity.CRITICAL -> StatusRed
        IssueSeverity.HIGH -> StatusOrange
        IssueSeverity.MEDIUM -> StatusAmber
        else -> StatusGreen
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel,
    onOpenHost: (String) -> Unit,
    onAddHost: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Stackward") },
                actions = {
                    if (uiState.isCheckingAll) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    } else if (uiState.hosts.isNotEmpty()) {
                        IconButton(onClick = viewModel::checkAll) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Check all hosts")
                        }
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddHost) {
                Icon(Icons.Filled.Add, contentDescription = "Add host")
            }
        },
    ) { padding ->
        if (uiState.hosts.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text("No hosts yet — tap + to connect one.")
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                uiState.batchMessage?.let { message ->
                    item {
                        Text(
                            message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                }
                items(uiState.hosts, key = { it.profile.id }) { host ->
                    HostRow(
                        host = host,
                        onClick = { onOpenHost(host.profile.id) },
                        onCheckNow = { viewModel.checkNow(host.profile.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun HostRow(
    host: HostSummary,
    onClick: () -> Unit,
    onCheckNow: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(color = statusColor(host), shape = CircleShape),
                )
                Text(
                    text = host.profile.host,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 12.dp).weight(1f),
                )
                if (host.isChecking) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                } else {
                    IconButton(onClick = onCheckNow) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Check now")
                    }
                }
            }
            Text(
                text = statusLabel(host),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, start = 24.dp),
            )
            host.error?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp, start = 24.dp),
                )
            }
        }
    }
}

private fun statusLabel(host: HostSummary): String {
    val now = System.currentTimeMillis()
    val age = host.lastCheckedAt?.let { HostHealthMapper.ageLabel(it, now) }
    val blind = if (host.blindSpots > 0) " · ${host.blindSpots} blind spot(s)" else ""
    return when (host.health) {
        HostHealth.NEVER_CHECKED -> "Never checked"
        HostHealth.UNREACHABLE -> {
            val failedAgo = host.lastFailureAt?.let { HostHealthMapper.ageLabel(it, now) } ?: "just now"
            val last = age?.let { " — last good check $it" } ?: ""
            "Unreachable ($failedAgo)$last"
        }
        HostHealth.STALE -> "Stale — checked $age${if (host.issueCount > 0) " · ${host.issueCount} issue(s)" else ""}$blind"
        HostHealth.CLEAN ->
            if (host.blindSpots > 0) "No issues found$blind — checked $age" else "No issues — checked $age"
        HostHealth.ISSUES -> "${host.issueCount} issue(s)$blind — checked $age"
    }
}
