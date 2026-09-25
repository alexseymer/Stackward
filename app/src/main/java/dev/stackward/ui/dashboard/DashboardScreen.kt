package dev.stackward.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import dev.stackward.check.IssueSeverity
import dev.stackward.check.PollingMode
import java.text.DateFormat
import java.util.Date

private val StatusGreen = Color(0xFF2E7D32)
private val StatusAmber = Color(0xFFF9A825)
private val StatusOrange = Color(0xFFEF6C00)
private val StatusRed = Color(0xFFC62828)
private val StatusGray = Color(0xFF9E9E9E)

private fun statusColor(severity: IssueSeverity?): Color = when (severity) {
    null -> StatusGray
    IssueSeverity.LOW -> StatusGreen
    IssueSeverity.MEDIUM -> StatusAmber
    IssueSeverity.HIGH -> StatusOrange
    IssueSeverity.CRITICAL -> StatusRed
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel,
    onOpenHost: (String) -> Unit,
    onAddHost: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Stackward") },
                actions = {
                    IconButton(onClick = onOpenLogs) {
                        Icon(Icons.Filled.Article, contentDescription = "Raw logs & AI summary")
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
                items(uiState.hosts, key = { it.profile.id }) { host ->
                    HostRow(
                        host = host,
                        onClick = { onOpenHost(host.profile.id) },
                        onCheckNow = { viewModel.checkNow(host.profile.id) },
                        onPollingModeChange = { mode -> viewModel.setPollingMode(host.profile.id, mode) },
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
    onPollingModeChange: (PollingMode) -> Unit,
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
                        .background(color = statusColor(host.maxSeverity), shape = CircleShape),
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
            Row(
                modifier = Modifier.padding(top = 8.dp, start = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = host.pollingMode == PollingMode.MANUAL,
                    onClick = { onPollingModeChange(PollingMode.MANUAL) },
                    label = { Text("Manual") },
                )
                FilterChip(
                    selected = host.pollingMode == PollingMode.AUTO_4H,
                    onClick = { onPollingModeChange(PollingMode.AUTO_4H) },
                    label = { Text("Auto 4h") },
                )
            }
        }
    }
}

private fun statusLabel(host: HostSummary): String {
    val lastChecked = host.lastCheckedAt?.let {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it))
    } ?: "never checked"
    return if (host.maxSeverity == null && host.issueCount == 0) {
        "No issues — last checked $lastChecked"
    } else {
        "${host.issueCount} issue(s) — last checked $lastChecked"
    }
}
