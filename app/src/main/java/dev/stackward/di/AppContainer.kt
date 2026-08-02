package dev.stackward.di

import android.content.Context
import dev.stackward.connection.ConnectionHealthRepository
import dev.stackward.connection.HostKeyPinStore
import dev.stackward.connection.SshConnectionManager
import dev.stackward.crypto.AgentKeyManager
import dev.stackward.inference.DeviceCapabilityChecker
import dev.stackward.inference.GemmaInferenceEngine
import dev.stackward.inference.LogSummarizer
import dev.stackward.inference.ModelImporter
import dev.stackward.inference.ModelRepository
import dev.stackward.logs.LogDigestStore
import dev.stackward.logs.LogReader
import dev.stackward.onboarding.OnboardingFlow
import dev.stackward.onboarding.ServerProfileRepository
import dev.stackward.permissions.AuditLogRepository
import dev.stackward.permissions.PermissionEngine
import dev.stackward.permissions.PermissionExecutor
import dev.stackward.permissions.Tier1RulesRepository
import dev.stackward.security.KeyRotationService
import dev.stackward.security.PanicRevokeService
import dev.stackward.security.SecuritySettingsRepository
import dev.stackward.security.Tier1RulesSyncer

class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val keyManager = AgentKeyManager(appContext)
    val securitySettings = SecuritySettingsRepository(appContext)
    val pinStore = HostKeyPinStore(appContext)
    val connectionHealth = ConnectionHealthRepository(appContext)
    val profileRepository = ServerProfileRepository(appContext)
    val ssh = SshConnectionManager(keyManager, pinStore, securitySettings, connectionHealth)
    val logReader = LogReader(ssh)
    val logDigestStore = LogDigestStore(appContext)
    val modelRepository = ModelRepository(appContext)
    val deviceCapabilityChecker = DeviceCapabilityChecker(appContext)
    val gemmaEngine = GemmaInferenceEngine(appContext)
    val modelImporter = ModelImporter(appContext, modelRepository)
    val logSummarizer = LogSummarizer(gemmaEngine, modelRepository)
    val tier1RulesRepository = Tier1RulesRepository(appContext)
    val auditLogRepository = AuditLogRepository(appContext)
    val permissionEngine = PermissionEngine(tier1RulesRepository.loadRules())
    val permissionExecutor = PermissionExecutor(permissionEngine, auditLogRepository)
    val tier1RulesSyncer = Tier1RulesSyncer(
        ssh = ssh,
        tier1RulesRepository = tier1RulesRepository,
        permissionEngine = permissionEngine,
        securitySettings = securitySettings,
    )
    val keyRotationService = KeyRotationService(
        keyManager = keyManager,
        securitySettings = securitySettings,
        ssh = ssh,
        auditLog = auditLogRepository,
    )
    val panicRevokeService = PanicRevokeService(
        ssh = ssh,
        keyManager = keyManager,
        securitySettings = securitySettings,
        profileRepository = profileRepository,
        pinStore = pinStore,
        auditLog = auditLogRepository,
        tier1RulesRepository = tier1RulesRepository,
        connectionHealth = connectionHealth,
    )
    val onboardingFlow = OnboardingFlow(
        context = appContext,
        keyManager = keyManager,
        ssh = ssh,
        pinStore = pinStore,
        profileRepository = profileRepository,
    )
}
