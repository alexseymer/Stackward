package dev.stackward.ui.settings

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.stackward.ui.security.BiometricGate
import dev.stackward.util.findFragmentActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    onPanicRevoked: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val biometricGate = remember(context) {
        BiometricGate(context.findFragmentActivity())
    }
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) {
            viewModel.exportAuditLog(uri)
        }
    }
    val panicExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val content = viewModel.consumePendingAuditExport()
        if (uri != null && content != null) {
            context.contentResolver.openOutputStream(uri)?.use { stream ->
                stream.write(content.toByteArray())
            }
        }
    }

    if (uiState.showRotateConfirm) {
        AlertDialog(
            onDismissRequest = viewModel::dismissRotateConfirm,
            title = { Text("Rotate SSH key?") },
            text = {
                Text(
                    "Generates a new device key, pushes it to the server, verifies access, " +
                        "then revokes the old key. Requires biometric approval.",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.dismissRotateConfirm()
                        viewModel.launchRotateWithBiometric(biometricGate)
                    },
                    enabled = !uiState.isBusy,
                ) {
                    Text("Rotate")
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissRotateConfirm) {
                    Text("Cancel")
                }
            },
        )
    }

    if (uiState.showPanicConfirm) {
        AlertDialog(
            onDismissRequest = viewModel::dismissPanicConfirm,
            title = { Text("Emergency revoke?") },
            text = {
                Text(
                    "Removes all gemma-agent keys on the server and wipes local credentials. " +
                        "You will need to re-bootstrap. Export audit log first if needed.",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.dismissPanicConfirm()
                        viewModel.launchPanicWithBiometric(biometricGate, onPanicRevoked) {
                            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                            panicExportLauncher.launch("stackward-audit-$stamp.json")
                        }
                    },
                    enabled = !uiState.isBusy,
                ) {
                    Text("Revoke everything")
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissPanicConfirm) {
                    Text("Cancel")
                }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Security & settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (uiState.isBusy) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator()
                }
            }

            ConnectionCard(uiState = uiState)

            Tier1Card(
                uiState = uiState,
                onSync = viewModel::syncTier1Rules,
                onMarkReviewed = viewModel::markTier1Reviewed,
            )

            AuditCard(
                entryCount = uiState.auditEntryCount,
                onExport = {
                    val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                    exportLauncher.launch("stackward-audit-$stamp.json")
                },
            )

            SecurityActionsCard(
                onRotate = viewModel::requestRotateKey,
                onPanic = viewModel::requestPanicRevoke,
                enabled = !uiState.isBusy && uiState.profileHost != null,
            )

            uiState.statusMessage?.let { message ->
                Text(message, color = MaterialTheme.colorScheme.primary)
            }
            uiState.error?.let { error ->
                Text(error, color = MaterialTheme.colorScheme.error)
            }
            uiState.tier1SyncResult?.let { result ->
                Tier1SyncResultCard(result = result)
            }
        }
    }
}

@Composable
private fun ConnectionCard(uiState: SettingsUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Connection health", style = MaterialTheme.typography.titleSmall)
            if (uiState.profileHost == null) {
                Text("No server provisioned.")
                return@Column
            }
            Text("${uiState.profileHost}:${uiState.profilePort}")
            uiState.jumpHost?.let { jump ->
                Text("Jump host: $jump", style = MaterialTheme.typography.bodySmall)
            }
            uiState.lastSuccessLabel?.let {
                Text("Last success: $it", style = MaterialTheme.typography.bodySmall)
            }
            uiState.lastFailureLabel?.let {
                Text("Last failure: $it", style = MaterialTheme.typography.bodySmall)
            }
            uiState.lastError?.let {
                Text("Last error: $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
            Text(
                "SSH commands retry with exponential backoff on transient failures.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Tier1Card(
    uiState: SettingsUiState,
    onSync: () -> Unit,
    onMarkReviewed: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = if (uiState.tier1ReviewDue) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Tier 1 rules", style = MaterialTheme.typography.titleSmall)
            if (uiState.tier1ReviewDue) {
                Text(
                    "Review due — sync sudoers.d from server or mark reviewed.",
                    color = MaterialTheme.colorScheme.tertiary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            uiState.lastTier1ReviewLabel?.let {
                Text("Last reviewed: $it", style = MaterialTheme.typography.labelSmall)
            }
            if (uiState.tier1Rules.isEmpty()) {
                Text("No local Tier 1 rules.", style = MaterialTheme.typography.bodySmall)
            } else {
                uiState.tier1Rules.forEach { rule ->
                    Text(rule, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onSync, modifier = Modifier.weight(1f)) {
                    Text("Sync from server")
                }
                OutlinedButton(onClick = onMarkReviewed, modifier = Modifier.weight(1f)) {
                    Text("Mark reviewed")
                }
            }
        }
    }
}

@Composable
private fun Tier1SyncResultCard(result: dev.stackward.security.Tier1SyncResult) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Sync diff", style = MaterialTheme.typography.titleSmall)
            if (result.onlyOnServer.isNotEmpty()) {
                Text("Only on server:", style = MaterialTheme.typography.labelMedium)
                result.onlyOnServer.forEach { Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
            }
            if (result.onlyLocal.isNotEmpty()) {
                Text("Only local:", style = MaterialTheme.typography.labelMedium)
                result.onlyLocal.forEach { Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
            }
            if (result.onlyOnServer.isEmpty() && result.onlyLocal.isEmpty()) {
                Text("Local and server rules match.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun AuditCard(
    entryCount: Int,
    onExport: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Audit log", style = MaterialTheme.typography.titleSmall)
            Text("$entryCount entries stored locally (encrypted).")
            Button(onClick = onExport, modifier = Modifier.fillMaxWidth()) {
                Text("Export JSON")
            }
        }
    }
}

@Composable
private fun SecurityActionsCard(
    onRotate: () -> Unit,
    onPanic: () -> Unit,
    enabled: Boolean,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Credential actions", style = MaterialTheme.typography.titleSmall)
            OutlinedButton(onClick = onRotate, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                Text("Rotate SSH key")
            }
            Button(onClick = onPanic, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                Text("Emergency revoke")
            }
        }
    }
}
