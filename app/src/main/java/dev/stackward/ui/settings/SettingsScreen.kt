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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.stackward.R
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
            title = { Text(stringResource(R.string.settings_rotate_title)) },
            text = {
                Text(stringResource(R.string.settings_rotate_body))
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.dismissRotateConfirm()
                        viewModel.launchRotateWithBiometric(biometricGate)
                    },
                    enabled = !uiState.isBusy,
                ) {
                    Text(stringResource(R.string.settings_rotate_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissRotateConfirm) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (uiState.showPanicConfirm) {
        AlertDialog(
            onDismissRequest = viewModel::dismissPanicConfirm,
            title = { Text(stringResource(R.string.settings_panic_title)) },
            text = {
                Text(stringResource(R.string.settings_panic_body))
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
                    Text(stringResource(R.string.settings_panic_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissPanicConfirm) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
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
        }
    }
}

@Composable
private fun ConnectionCard(uiState: SettingsUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.settings_connection_title), style = MaterialTheme.typography.titleSmall)
            if (uiState.profileHost == null) {
                Text(stringResource(R.string.settings_no_server))
                return@Column
            }
            Text("${uiState.profileHost}:${uiState.profilePort}")
            uiState.jumpHost?.let { jump ->
                Text(stringResource(R.string.settings_jump_host, jump), style = MaterialTheme.typography.bodySmall)
            }
            uiState.lastSuccessLabel?.let {
                Text(stringResource(R.string.settings_last_success, it), style = MaterialTheme.typography.bodySmall)
            }
            uiState.lastFailureLabel?.let {
                Text(stringResource(R.string.settings_last_failure, it), style = MaterialTheme.typography.bodySmall)
            }
            uiState.lastError?.let {
                Text(stringResource(R.string.settings_last_error, it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
            Text(
                stringResource(R.string.settings_retry_note),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
            Text(stringResource(R.string.settings_audit_title), style = MaterialTheme.typography.titleSmall)
            Text(LocalContext.current.resources.getQuantityString(R.plurals.settings_audit_entries, entryCount, entryCount))
            Button(onClick = onExport, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_audit_export))
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
            Text(stringResource(R.string.settings_credentials_title), style = MaterialTheme.typography.titleSmall)
            OutlinedButton(onClick = onRotate, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_rotate_button))
            }
            Button(onClick = onPanic, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_panic_button))
            }
        }
    }
}
