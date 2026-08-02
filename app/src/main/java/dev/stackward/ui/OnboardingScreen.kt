package dev.stackward.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.stackward.connection.HostKeyFingerprint
import dev.stackward.ui.onboarding.OnboardingViewModel
import dev.stackward.ui.onboarding.ProvisionStep
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    viewModel: OnboardingViewModel,
    onProvisioned: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val clipboardManager = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(uiState.message) {
        uiState.message?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Stackward Setup") })
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (uiState.step) {
                ProvisionStep.SUCCESS -> SuccessContent(
                    uiState = uiState,
                    onViewLogs = onProvisioned,
                )
                ProvisionStep.PROVISIONING -> ProvisioningContent()
                ProvisionStep.SCRIPT_PREVIEW -> ScriptPreviewContent(
                    uiState = uiState,
                    onBack = viewModel::backToInput,
                    onProvision = viewModel::startBootstrap,
                )
                ProvisionStep.INPUT -> InputContent(
                    uiState = uiState,
                    viewModel = viewModel,
                    clipboardManager = clipboardManager,
                    snackbarHostState = snackbarHostState,
                    scope = scope,
                )
            }

            uiState.error?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun InputContent(
    uiState: dev.stackward.ui.onboarding.OnboardingUiState,
    viewModel: OnboardingViewModel,
    clipboardManager: androidx.compose.ui.platform.ClipboardManager,
    snackbarHostState: SnackbarHostState,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    Text(
        text = "Phase 0/1 — Connect your server and generate a secure SSH identity.",
        style = MaterialTheme.typography.bodyMedium,
    )

    OutlinedTextField(
        value = uiState.host,
        onValueChange = viewModel::onHostChange,
        label = { Text("Host / IP") },
        placeholder = { Text("192.168.1.10") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    OutlinedTextField(
        value = uiState.port,
        onValueChange = viewModel::onPortChange,
        label = { Text("SSH Port") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    OutlinedTextField(
        value = uiState.adminUsername,
        onValueChange = viewModel::onAdminUsernameChange,
        label = { Text("Admin SSH user") },
        placeholder = { Text("root") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    OutlinedTextField(
        value = uiState.adminCredential,
        onValueChange = viewModel::onAdminCredentialChange,
        label = { Text("One-time admin password") },
        placeholder = { Text("Used once for bootstrap, never stored") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    Button(
        onClick = viewModel::generateSshKey,
        enabled = !uiState.isGeneratingKey,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(Icons.Default.Key, contentDescription = null)
        Text(
            text = if (uiState.isGeneratingKey) "Generating…" else "Generate SSH Key",
            modifier = Modifier.padding(start = 8.dp),
        )
    }

    uiState.publicKeyOpenSsh?.let { publicKey ->
        PublicKeyCard(
            publicKey = publicKey,
            usesHardwareKeystore = uiState.usesHardwareKeystore,
            onCopy = {
                clipboardManager.setText(AnnotatedString(publicKey))
                scope.launch { snackbarHostState.showSnackbar("Public key copied") }
            },
        )
    }

    Button(
        onClick = viewModel::showBootstrapScript,
        enabled = uiState.canPreviewScript,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(Icons.Default.Visibility, contentDescription = null)
        Text("Review bootstrap script", modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun ScriptPreviewContent(
    uiState: dev.stackward.ui.onboarding.OnboardingUiState,
    onBack: () -> Unit,
    onProvision: () -> Unit,
) {
    Text(
        text = "Review the exact script that will run on your server as root.",
        style = MaterialTheme.typography.bodyMedium,
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("bootstrap_linux.sh", style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.height(8.dp))
            SelectionContainer {
                Text(
                    text = uiState.bootstrapScript.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }

    Text(
        text = "Admin credential is used once for sudo and then discarded from the app.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
        Text("Back")
    }

    Button(
        onClick = onProvision,
        enabled = uiState.canStartBootstrap && !uiState.isProvisioning,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Run bootstrap on server")
    }
}

@Composable
private fun ProvisioningContent() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Text("Provisioning server and verifying gemma-agent access…")
        CircularProgressIndicator()
    }
}

@Composable
private fun SuccessContent(
    uiState: dev.stackward.ui.onboarding.OnboardingUiState,
    onViewLogs: () -> Unit,
) {
    val profile = uiState.provisionedProfile ?: return

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Default.CheckCircle, contentDescription = null)
            Text("Server provisioned", style = MaterialTheme.typography.titleMedium)
            Text("Host: ${profile.host}:${profile.port}")
            Text("Agent user: gemma-agent")
            Text(
                text = "Host key: ${HostKeyFingerprint.openSshLabel(profile.hostKeyFingerprint)}",
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
            )
            uiState.verificationOutput?.let { output ->
                Text("Verification:", style = MaterialTheme.typography.labelMedium)
                Text(output, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = onViewLogs, modifier = Modifier.fillMaxWidth()) {
                Text("View logs")
            }
        }
    }
}

@Composable
private fun PublicKeyCard(
    publicKey: String,
    usesHardwareKeystore: Boolean,
    onCopy: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("SSH Public Key", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            SelectionContainer {
                Text(
                    text = publicKey,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = if (usesHardwareKeystore) {
                    "Stored in Android Keystore (biometric-gated)"
                } else {
                    "Stored encrypted (software Ed25519 — API < 33)"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(onClick = onCopy, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.ContentCopy, contentDescription = null)
                Text("Copy public key", modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}
