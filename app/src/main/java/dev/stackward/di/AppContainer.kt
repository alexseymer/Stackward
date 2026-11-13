package dev.stackward.di

import android.content.Context
import dev.stackward.check.CheckResultStore
import dev.stackward.check.CheckScriptRunner
import dev.stackward.connection.ConnectionHealthRepository
import dev.stackward.connection.HostKeyPinStore
import dev.stackward.connection.SshConnectionManager
import dev.stackward.crypto.AgentKeyManager
import dev.stackward.onboarding.OnboardingFlow
import dev.stackward.onboarding.ServerProfileRepository
import dev.stackward.permissions.AuditLogRepository
import dev.stackward.security.KeyRotationService
import dev.stackward.security.PanicRevokeService
import dev.stackward.security.SecuritySettingsRepository

class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val keyManager = AgentKeyManager(appContext)
    val securitySettings = SecuritySettingsRepository(appContext)
    val pinStore = HostKeyPinStore(appContext)
    val connectionHealth = ConnectionHealthRepository(appContext)
    val profileRepository = ServerProfileRepository(appContext)
    val ssh = SshConnectionManager(keyManager, pinStore, securitySettings, connectionHealth)
    val checkScriptRunner = CheckScriptRunner(ssh)
    val checkResultStore = CheckResultStore(appContext)
    val auditLogRepository = AuditLogRepository(appContext)
    val keyRotationService = KeyRotationService(
        keyManager = keyManager,
        securitySettings = securitySettings,
        ssh = ssh,
        auditLog = auditLogRepository,
    )
    val panicRevokeService = PanicRevokeService(
        context = appContext,
        ssh = ssh,
        keyManager = keyManager,
        securitySettings = securitySettings,
        profileRepository = profileRepository,
        pinStore = pinStore,
        auditLog = auditLogRepository,
        connectionHealth = connectionHealth,
        checkResultStore = checkResultStore,
    )
    val onboardingFlow = OnboardingFlow(
        context = appContext,
        keyManager = keyManager,
        ssh = ssh,
        pinStore = pinStore,
        profileRepository = profileRepository,
    )
}
