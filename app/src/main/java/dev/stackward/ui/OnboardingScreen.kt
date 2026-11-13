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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.stackward.BuildConfig
import dev.stackward.R
import dev.stackward.connection.HostKeyFingerprint
import dev.stackward.onboarding.BootstrapAuthMethod
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

    val commandCopiedMessage = stringResource(R.string.onb_command_copied)

    LaunchedEffect(uiState.message) {
        uiState.message?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.onb_title)) },
                navigationIcon = {
                    if (onCancel != null) {
                        IconButton(onClick = onCancel) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
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
                            snackbarHostState.showSnackbar(commandCopiedMessage)
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
        text = stringResource(R.string.onb_prep_title),
        style = MaterialTheme.typography.titleMedium,
    )
    Text(
        text = stringResource(R.string.onb_prep_body),
        style = MaterialTheme.typography.bodyMedium,
    )
    Text(
        text = stringResource(R.string.onb_prep_create),
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
                Text(stringResource(R.string.onb_copy_command), modifier = Modifier.padding(start = 8.dp))
            }
        }
    }

    Text(
        text = stringResource(R.string.onb_prep_after),
        style = MaterialTheme.typography.bodyMedium,
    )
    Text(
        text = stringResource(R.string.onb_build, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.onb_continue))
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
    val keyCopiedMessage = stringResource(R.string.onb_key_copied)
    val agentKeyLabel = stringResource(R.string.onb_agent_key_label)
    Text(
        text = stringResource(R.string.onb_input_intro),
        style = MaterialTheme.typography.bodyMedium,
    )

    OutlinedTextField(
        value = uiState.host,
        onValueChange = viewModel::onHostChange,
        label = { Text(stringResource(R.string.onb_host)) },
        placeholder = { Text("192.168.1.10") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    OutlinedTextField(
        value = uiState.port,
        onValueChange = viewModel::onPortChange,
        label = { Text(stringResource(R.string.onb_port)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    OutlinedTextField(
        value = uiState.agentUsername,
        onValueChange = viewModel::onAgentUsernameChange,
        label = { Text(stringResource(R.string.onb_user)) },
        placeholder = { Text(ServerProfile.DEFAULT_AGENT_USERNAME) },
        supportingText = {
            Text(stringResource(R.string.onb_user_hint))
        },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    Text(stringResource(R.string.onb_login_method), style = MaterialTheme.typography.titleSmall)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = uiState.authMethod == BootstrapAuthMethod.PASSWORD,
            onClick = { viewModel.onAuthMethodChange(BootstrapAuthMethod.PASSWORD) },
            label = { Text(stringResource(R.string.onb_login_password)) },
        )
        FilterChip(
            selected = uiState.authMethod == BootstrapAuthMethod.PRIVATE_KEY,
            onClick = { viewModel.onAuthMethodChange(BootstrapAuthMethod.PRIVATE_KEY) },
            label = { Text(stringResource(R.string.onb_login_key)) },
        )
    }

    when (uiState.authMethod) {
        BootstrapAuthMethod.PASSWORD -> {
            OutlinedTextField(
                value = sshPasswordDraft,
                onValueChange = onSshPasswordChange,
                label = { Text(stringResource(R.string.onb_password)) },
                placeholder = { Text(stringResource(R.string.onb_password_hint)) },
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
                    agentKeyLabel
                } else {
                    privateKeyPemDraft
                },
                onValueChange = { value ->
                    if (value == agentKeyLabel) return@OutlinedTextField
                    preferPemPaste = true
                    onPrivateKeyPemChange(value)
                },
                label = { Text(stringResource(R.string.onb_pem)) },
                placeholder = { Text("-----BEGIN OPENSSH PRIVATE KEY-----") },
                supportingText = {
                    Text(
                        if (agentKeyFillsField) {
                            stringResource(R.string.onb_pem_filled)
                        } else {
                            stringResource(R.string.onb_pem_empty)
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
                    Text(stringResource(R.string.onb_pem_other))
                }
            } else {
                OutlinedTextField(
                    value = privateKeyPassphraseDraft,
                    onValueChange = onPrivateKeyPassphraseChange,
                    label = { Text(stringResource(R.string.onb_passphrase)) },
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
        label = { Text(stringResource(R.string.onb_knock)) },
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
            Text(stringResource(R.string.onb_jump_use), style = MaterialTheme.typography.bodyLarge)
            Text(
                text = stringResource(R.string.onb_jump_hint),
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
            label = { Text(stringResource(R.string.onb_jump_host)) },
            placeholder = { Text("bastion.example.com") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = uiState.jumpHostPort,
            onValueChange = viewModel::onJumpHostPortChange,
            label = { Text(stringResource(R.string.onb_jump_port)) },
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
            text = if (uiState.isGeneratingKey) stringResource(R.string.onb_generating) else stringResource(R.string.onb_generate),
            modifier = Modifier.padding(start = 8.dp),
        )
    }

    uiState.publicKeyOpenSsh?.let { publicKey ->
        PublicKeyCard(
            publicKey = publicKey,
            usesHardwareKeystore = uiState.usesHardwareKeystore,
            onCopy = {
                clipboardManager.setText(AnnotatedString(publicKey))
                scope.launch { snackbarHostState.showSnackbar(keyCopiedMessage) }
            },
        )
    }

    OutlinedButton(onClick = viewModel::backToPrep, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.onb_back_prep))
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
            Text(stringResource(R.string.onb_testing))
        } else {
            Icon(Icons.Default.Lock, contentDescription = null)
            Text(stringResource(R.string.onb_connect_review), modifier = Modifier.padding(start = 8.dp))
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
        text = stringResource(R.string.onb_confirm_title),
        style = MaterialTheme.typography.titleMedium,
    )
    Text(
        text = stringResource(R.string.onb_confirm_body, uiState.agentUsername),
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
                    text = stringResource(R.string.onb_elevated_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    text = stringResource(R.string.onb_elevated_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = uiState.elevatedPrivilegeAcknowledged,
                        onCheckedChange = onElevatedPrivilegeAcknowledgedChange,
                    )
                    Text(
                        text = stringResource(R.string.onb_elevated_ack),
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
        text = stringResource(R.string.onb_confirm_wipe),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.action_back))
    }

    Button(
        onClick = onInstall,
        enabled = uiState.canStartSetup && !uiState.isProvisioning,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.onb_install))
    }
}

@Composable
private fun ProvisioningContent() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Text(stringResource(R.string.onb_installing))
        Text(
            text = stringResource(R.string.onb_installing_jump),
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
            Text(stringResource(R.string.onb_done_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.onb_done_host, "${profile.host}:${profile.port}"))
            profile.jumpHost?.let { jump ->
                Text(stringResource(R.string.onb_done_jump, "$jump:${profile.jumpHostPort}"))
            }
            Text(stringResource(R.string.onb_done_user, profile.username))
            Text(
                text = stringResource(R.string.onb_done_hostkey, HostKeyFingerprint.openSshLabel(profile.hostKeyFingerprint)),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
            )
            profile.jumpHostKeyFingerprint?.let { jumpFp ->
                Text(
                    text = stringResource(R.string.onb_done_jumpkey, HostKeyFingerprint.openSshLabel(jumpFp)),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            uiState.verificationOutput?.let { output ->
                Text("Verification:", style = MaterialTheme.typography.labelMedium)
                Text(output, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = onViewLogs,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.action_done))
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
            Text(stringResource(R.string.onb_pubkey_title), style = MaterialTheme.typography.titleMedium)
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
                    stringResource(R.string.onb_key_keystore)
                } else {
                    stringResource(R.string.onb_key_software)
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(onClick = onCopy, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.ContentCopy, contentDescription = null)
                Text(stringResource(R.string.onb_copy_pubkey), modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

private fun devPrefillOrEmpty(value: String): String =
    if (BuildConfig.DEBUG && BuildConfig.DEV_PREFILL) value else ""
