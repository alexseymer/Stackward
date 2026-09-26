package dev.stackward.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.stackward.ui.analyzer.LogAnalyzerScreen
import dev.stackward.ui.analyzer.LogAnalyzerViewModel
import dev.stackward.ui.connections.ConnectionsScreen
import dev.stackward.ui.connections.ConnectionsViewModel
import dev.stackward.ui.logs.LogsScreen
import dev.stackward.ui.logs.LogsViewModel
import dev.stackward.ui.onboarding.OnboardingViewModel
import dev.stackward.ui.settings.SettingsScreen
import dev.stackward.ui.settings.SettingsViewModel

private enum class AppDestination {
    CONNECTIONS,
    ONBOARDING,
    LOGS,
    ANALYZER,
    SETTINGS,
}

@Composable
fun StackwardApp() {
    // Hub is always the start screen — never auto-open setup or the first host.
    // Key bump invalidates any previously saved LOGS/ONBOARDING instance state.
    var destination by rememberSaveable(key = "app_dest_v2") {
        mutableStateOf(AppDestination.CONNECTIONS)
    }
    var selectedProfileId by rememberSaveable(key = "selected_profile_v2") {
        mutableStateOf<String?>(null)
    }
    var analyzerInitialLogs by remember { mutableStateOf<String?>(null) }

    val connectionsViewModel: ConnectionsViewModel = viewModel()
    val onboardingViewModel: OnboardingViewModel = viewModel()
    val logsViewModel: LogsViewModel = viewModel()
    val logAnalyzerViewModel: LogAnalyzerViewModel = viewModel()
    val settingsViewModel: SettingsViewModel = viewModel()

    LaunchedEffect(destination, selectedProfileId) {
        when (destination) {
            AppDestination.CONNECTIONS -> connectionsViewModel.refresh()
            AppDestination.LOGS -> logsViewModel.reloadProfile(selectedProfileId)
            AppDestination.SETTINGS -> settingsViewModel.refresh(selectedProfileId)
            else -> Unit
        }
    }

    when (destination) {
        AppDestination.CONNECTIONS -> ConnectionsScreen(
            viewModel = connectionsViewModel,
            onOpenConnection = { profileId ->
                selectedProfileId = profileId
                destination = AppDestination.LOGS
            },
            onAddConnection = {
                selectedProfileId = null
                onboardingViewModel.startFresh()
                destination = AppDestination.ONBOARDING
            },
        )
        AppDestination.LOGS -> LogsScreen(
            viewModel = logsViewModel,
            onBack = {
                selectedProfileId = null
                destination = AppDestination.CONNECTIONS
            },
            onOpenSettings = { destination = AppDestination.SETTINGS },
            onOpenAnalyzer = { logs ->
                analyzerInitialLogs = logs
                destination = AppDestination.ANALYZER
            },
        )
        AppDestination.ANALYZER -> LogAnalyzerScreen(
            viewModel = logAnalyzerViewModel,
            initialLogs = analyzerInitialLogs,
            onBack = {
                analyzerInitialLogs = null
                destination = AppDestination.LOGS
            },
        )
        AppDestination.SETTINGS -> SettingsScreen(
            viewModel = settingsViewModel,
            onBack = { destination = AppDestination.LOGS },
            onPanicRevoked = {
                selectedProfileId = null
                onboardingViewModel.resetAfterRevoke()
                destination = AppDestination.CONNECTIONS
            },
        )
        AppDestination.ONBOARDING -> OnboardingScreen(
            viewModel = onboardingViewModel,
            onProvisioned = {
                selectedProfileId = null
                connectionsViewModel.refresh()
                destination = AppDestination.CONNECTIONS
            },
            onCancel = {
                destination = AppDestination.CONNECTIONS
            },
        )
    }
}
