package dev.stackward.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.stackward.BuildConfig
import dev.stackward.connection.HostKeyFingerprint
import dev.stackward.onboarding.BootstrapAuthMethod
import dev.stackward.onboarding.HostType
import dev.stackward.onboarding.OnboardingFlow
import dev.stackward.onboarding.ServerProfile
import dev.stackward.ui.onboarding.OnboardingUiState
import dev.stackward.ui.onboarding.OnboardingViewModel
import dev.stackward.ui.onboarding.ProvisionStep
import dev.stackward.ui.security.BiometricGate
import dev.stackward.util.findFragmentActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    viewModel: OnboardingViewModel,
    onProvisioned: () -> Unit = {},
    onCancel: (() -> Unit)? = null,
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val biometricGate = remember(context) {
        BiometricGate(context.findFragmentActivity())
    }
    val scope = rememberCoroutineScope()

    var sshPasswordDraft by remember {
        mutableStateOf(devPrefillOrEmpty(BuildConfig.DEV_SSH_PASSWORD))
    }
    var privateKeyPemDraft by remember { mutableStateOf("") }
    var privateKeyPassphraseDraft by remember { mutableStateOf("") }

    LaunchedEffect(uiState.secretsEpoch) {
        if (BuildConfig.DEBUG && BuildConfig.DEV_PREFILL) {
            sshPasswordDraft = BuildConfig.DEV_SSH_PASSWORD
            privateKeyPemDraft = ""
            privateKeyPassphraseDraft = ""
            viewModel.resealDevSecretDrafts()
        } else {
            sshPasswordDraft = ""
            privateKeyPemDraft = ""
            privateKeyPassphraseDraft = ""
        }
    }

    LaunchedEffect(uiState.message) {
        uiState.message?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Stackward Setup") },
                navigationIcon = {
                    if (onCancel != null) {
                        IconButton(onClick = onCancel) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
            )
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
                    biometricGate = biometricGate,
                    viewModel = viewModel,
                    onViewLogs = onProvisioned,
                )
                ProvisionStep.PROVISIONING -> ProvisioningContent()
                ProvisionStep.CONFIRM -> ConfirmContent(
                    uiState = uiState,
                    onBack = viewModel::backToInput,
                    onInstall = viewModel::startSetup,
                    onElevatedPrivilegeAcknowledgedChange = viewModel::onElevatedPrivilegeAcknowledgedChange,
                )
                ProvisionStep.INPUT -> InputContent(
                    uiState = uiState,
                    viewModel = viewModel,
                    clipboardManager = clipboardManager,
                    snackbarHostState = snackbarHostState,
                    scope = scope,
                    sshPasswordDraft = sshPasswordDraft,
                    privateKeyPemDraft = privateKeyPemDraft,
                    privateKeyPassphraseDraft = privateKeyPassphraseDraft,
                    onSshPasswordChange = { value ->
                        sshPasswordDraft = value
                        viewModel.onSshPasswordChange(value)
                    },
                    onPrivateKeyPemChange = { value ->
                        privateKeyPemDraft = value
                        viewModel.onPrivateKeyPemChange(value)
                    },
                    onPrivateKeyPassphraseChange = { value ->
                        privateKeyPassphraseDraft = value
                        viewModel.onPrivateKeyPassphraseChange(value)
                    },
                )
                ProvisionStep.PREP_INFO -> PrepInfoContent(
                    onContinue = viewModel::acknowledgePrepInfo,
                    onCopyCommand = {
                        clipboardManager.setText(AnnotatedString(OnboardingFlow.PREP_ADDUSER_COMMAND))
                        scope.launch {
                            snackbarHostState.showSnackbar("Command copied")
                        }
                    },
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
private fun PrepInfoContent(
    onContinue: () -> Unit,
    onCopyCommand: () -> Unit,
) {
    Icon(
        Icons.Default.Info,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
    )
    Text(
        text = "Choose the SSH identity",
        style = MaterialTheme.typography.titleMedium,
    )
    Text(
        text = "Recommended default: a dedicated low-privilege user that can only work " +
            "inside its own home directory. You choose how powerful the remote identity is — " +
            "elevated accounts are allowed after an explicit risk acknowledgment.",
        style = MaterialTheme.typography.bodyMedium,
    )
    Text(
        text = "On a Debian-based host, an administrator can create the recommended user with:",
        style = MaterialTheme.typography.bodyMedium,
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SelectionContainer {
                Text(
                    text = OnboardingFlow.PREP_ADDUSER_COMMAND,
                    style = MaterialTheme.typography.bodyLarge,
                    fontFamily = FontFamily.Monospace,
                )
            }
            TextButton(onClick = onCopyCommand) {
                Icon(Icons.Default.ContentCopy, contentDescription = null)
                Text("Copy command", modifier = Modifier.padding(start = 8.dp))
            }
        }
    }

    Text(
        text = "After the user exists, this app will use the account password once — " +
            "like ssh-copy-id — to install this phone’s SSH public key into " +
            "~/.ssh/authorized_keys. The password is then wiped and never stored.",
        style = MaterialTheme.typography.bodyMedium,
    )
    Text(
        text = "Build ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
        Text("Continue to connection details")
    }
}

@Composable
private fun InputContent(
    uiState: OnboardingUiState,
    viewModel: OnboardingViewModel,
    clipboardManager: androidx.compose.ui.platform.ClipboardManager,
    snackbarHostState: SnackbarHostState,
    scope: CoroutineScope,
    sshPasswordDraft: String,
    privateKeyPemDraft: String,
    privateKeyPassphraseDraft: String,
    onSshPasswordChange: (String) -> Unit,
    onPrivateKeyPemChange: (String) -> Unit,
    onPrivateKeyPassphraseChange: (String) -> Unit,
) {
    Text(
        text = "Connect as the SSH user you chose. The one-time password is Keystore-encrypted " +
            "in memory only, used to install your public key, then wiped forever.",
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
        value = uiState.agentUsername,
        onValueChange = viewModel::onAgentUsernameChange,
        label = { Text("Agent SSH user") },
        placeholder = { Text(ServerProfile.DEFAULT_AGENT_USERNAME) },
        supportingText = {
            Text("Must already exist. Default: stackward-agent. Elevated accounts need acknowledgment.")
        },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    Text("One-time login method", style = MaterialTheme.typography.titleSmall)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = uiState.authMethod == BootstrapAuthMethod.PASSWORD,
            onClick = { viewModel.onAuthMethodChange(BootstrapAuthMethod.PASSWORD) },
            label = { Text("Password (ssh-copy-id)") },
        )
        FilterChip(
            selected = uiState.authMethod == BootstrapAuthMethod.PRIVATE_KEY,
            onClick = { viewModel.onAuthMethodChange(BootstrapAuthMethod.PRIVATE_KEY) },
            label = { Text("Key already installed") },
        )
    }

    when (uiState.authMethod) {
        BootstrapAuthMethod.PASSWORD -> {
            OutlinedTextField(
                value = sshPasswordDraft,
                onValueChange = onSshPasswordChange,
                label = { Text("One-time SSH password") },
                placeholder = { Text("Used once to install the key — never stored") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        BootstrapAuthMethod.PRIVATE_KEY -> {
            var preferPemPaste by remember(uiState.publicKeyOpenSsh) { mutableStateOf(false) }
            val agentKeyFillsField =
                !preferPemPaste &&
                    privateKeyPemDraft.isBlank() &&
                    uiState.publicKeyOpenSsh != null
            OutlinedTextField(
                value = if (agentKeyFillsField) {
                    AGENT_KEY_PRIVATE_FIELD_LABEL
                } else {
                    privateKeyPemDraft
                },
                onValueChange = { value ->
                    if (value == AGENT_KEY_PRIVATE_FIELD_LABEL) return@OutlinedTextField
                    preferPemPaste = true
                    onPrivateKeyPemChange(value)
                },
                label = { Text("Private key (PEM / OpenSSH)") },
                placeholder = { Text("-----BEGIN OPENSSH PRIVATE KEY-----") },
                supportingText = {
                    Text(
                        if (agentKeyFillsField) {
                            "Filled from Generate agent SSH key. Use this only if the public key is already on the server."
                        } else {
                            "Paste a bootstrap private key, or generate the agent key to fill this."
                        },
                    )
                },
                readOnly = agentKeyFillsField,
                minLines = 4,
                maxLines = 8,
                modifier = Modifier.fillMaxWidth(),
            )
            if (agentKeyFillsField) {
                TextButton(
                    onClick = { preferPemPaste = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Paste a different private key")
                }
            } else {
                OutlinedTextField(
                    value = privateKeyPassphraseDraft,
                    onValueChange = onPrivateKeyPassphraseChange,
                    label = { Text("Key passphrase (optional)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    OutlinedTextField(
        value = uiState.knockSequence,
        onValueChange = viewModel::onKnockSequenceChange,
        label = { Text("Port knock sequence (optional)") },
        placeholder = { Text("7000,8000,9000") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text("Use jump host", style = MaterialTheme.typography.bodyLarge)
            Text(
                text = "Same restricted user on bastion and target; key is installed on both",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = uiState.useJumpHost,
            onCheckedChange = viewModel::onUseJumpHostChange,
        )
    }

    if (uiState.useJumpHost) {
        OutlinedTextField(
            value = uiState.jumpHost,
            onValueChange = viewModel::onJumpHostChange,
            label = { Text("Jump host / bastion") },
            placeholder = { Text("bastion.example.com") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = uiState.jumpHostPort,
            onValueChange = viewModel::onJumpHostPortChange,
            label = { Text("Jump SSH port") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    Button(
        onClick = viewModel::generateSshKey,
        enabled = !uiState.isGeneratingKey,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(Icons.Default.Key, contentDescription = null)
        Text(
            text = if (uiState.isGeneratingKey) "Generating…" else "Generate agent SSH key",
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

    OutlinedButton(onClick = viewModel::backToPrep, modifier = Modifier.fillMaxWidth()) {
        Text("Back to prep")
    }

    Button(
        onClick = viewModel::continueToConfirm,
        enabled = uiState.canContinueToConfirm && !uiState.isTestingConnection,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (uiState.isTestingConnection) {
            CircularProgressIndicator(
                modifier = Modifier
                    .size(18.dp)
                    .padding(end = 8.dp),
                strokeWidth = 2.dp,
            )
            Text("Testing connection…")
        } else {
            Icon(Icons.Default.Lock, contentDescription = null)
            Text("Connect & review key install", modifier = Modifier.padding(start = 8.dp))
        }
    }

    uiState.connectionProbe?.let { probe ->
        Text(
            text = probe,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ConfirmContent(
    uiState: OnboardingUiState,
    onBack: () -> Unit,
    onInstall: () -> Unit,
    onElevatedPrivilegeAcknowledgedChange: (Boolean) -> Unit,
) {
    Text(
        text = "Confirm key install",
        style = MaterialTheme.typography.titleMedium,
    )
    Text(
        text = "Stackward will append this device’s public key to " +
            "${uiState.agentUsername}’s ~/.ssh/authorized_keys. " +
            "Optional host bootstrap scripts are separate admin tools and are not run by the app.",
        style = MaterialTheme.typography.bodyMedium,
    )

    uiState.connectionProbe?.let { probe ->
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            SelectionContainer {
                Text(
                    text = probe,
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }

    if (uiState.elevatedPrivilegeDetected) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
            ),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Elevated privilege detected",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    text = "This account is root and/or has passwordless sudo. " +
                        "That is your choice, but a compromised phone could do far more damage. " +
                        "The recommended default remains a restricted agent user.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = uiState.elevatedPrivilegeAcknowledged,
                        onCheckedChange = onElevatedPrivilegeAcknowledgedChange,
                    )
                    Text(
                        text = "I understand and want to use this elevated identity",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        SelectionContainer {
            Text(
                text = uiState.setupPreview.orEmpty(),
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }

    Text(
        text = "After a successful install, the one-time password is wiped from memory and is never written to disk.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
        Text("Back")
    }

    Button(
        onClick = onInstall,
        enabled = uiState.canStartSetup && !uiState.isProvisioning,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Install SSH key & verify")
    }
}

@Composable
private fun ProvisioningContent() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Text("Installing authorized_keys in the agent home directory and verifying key login…")
        Text(
            text = "With a jump host, the bastion key is installed first, then the target.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        CircularProgressIndicator()
    }
}

@Composable
private fun SuccessContent(
    uiState: OnboardingUiState,
    biometricGate: BiometricGate,
    viewModel: OnboardingViewModel,
    onViewLogs: () -> Unit,
) {
    val profile = uiState.provisionedProfile ?: return

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Default.CheckCircle, contentDescription = null)
            Text("Agent key installed", style = MaterialTheme.typography.titleMedium)
            Text("Host: ${profile.host}:${profile.port}")
            profile.jumpHost?.let { jump ->
                Text("Jump host: $jump:${profile.jumpHostPort}")
            }
            Text("Host type: ${profile.hostType.name.lowercase()}")
            Text("Agent user: ${profile.username}")
            Text(
                text = "Host key: ${HostKeyFingerprint.openSshLabel(profile.hostKeyFingerprint)}",
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
            )
            profile.jumpHostKeyFingerprint?.let { jumpFp ->
                Text(
                    text = "Jump key: ${HostKeyFingerprint.openSshLabel(jumpFp)}",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            uiState.verificationOutput?.let { output ->
                Text("Verification:", style = MaterialTheme.typography.labelMedium)
                Text(output, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }

            if (profile.hostType == HostType.PROXMOX) {
                when {
                    uiState.proxmoxTokenPending -> {
                        Text(
                            text = "Proxmox API token ready — store it with biometrics to enable VM/LXC monitoring.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Button(
                            onClick = { viewModel.storeProxmoxTokenWithBiometric(biometricGate) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Store Proxmox token")
                        }
                    }
                    else -> {
                        Text(
                            text = "Proxmox API access requires an admin-created token out-of-band " +
                                "(this app does not run pveum or elevate).",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = onViewLogs,
                enabled = !uiState.proxmoxTokenPending,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Done — view connections")
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
            Text("Agent SSH public key", style = MaterialTheme.typography.titleMedium)
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
                    "Stored encrypted (software Ed25519 fallback)"
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

private const val AGENT_KEY_PRIVATE_FIELD_LABEL = "On-device agent SSH key"

private fun devPrefillOrEmpty(value: String): String =
    if (BuildConfig.DEBUG && BuildConfig.DEV_PREFILL) value else ""
