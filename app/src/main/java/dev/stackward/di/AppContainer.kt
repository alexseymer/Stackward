package dev.stackward.di

import android.content.Context
import dev.stackward.connection.HostKeyPinStore
import dev.stackward.connection.SshConnectionManager
import dev.stackward.crypto.AgentKeyManager
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
    val onboardingFlow = OnboardingFlow(
        context = appContext,
        keyManager = keyManager,
        ssh = ssh,
        pinStore = pinStore,
        profileRepository = profileRepository,
    )
}
