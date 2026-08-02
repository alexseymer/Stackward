package dev.stackward.di

import android.content.Context
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

class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val keyManager = AgentKeyManager(appContext)
    val pinStore = HostKeyPinStore(appContext)
    val profileRepository = ServerProfileRepository(appContext)
    val ssh = SshConnectionManager(keyManager, pinStore)
    val logReader = LogReader(ssh)
    val logDigestStore = LogDigestStore(appContext)
    val modelRepository = ModelRepository(appContext)
    val deviceCapabilityChecker = DeviceCapabilityChecker(appContext)
    val gemmaEngine = GemmaInferenceEngine(appContext)
    val modelImporter = ModelImporter(appContext, modelRepository)
    val logSummarizer = LogSummarizer(gemmaEngine, modelRepository)
    val onboardingFlow = OnboardingFlow(
        context = appContext,
        keyManager = keyManager,
        ssh = ssh,
        pinStore = pinStore,
        profileRepository = profileRepository,
    )
}
