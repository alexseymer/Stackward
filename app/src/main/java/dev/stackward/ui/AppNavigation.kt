package dev.stackward.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.stackward.ui.analyzer.LogAnalyzerScreen
import dev.stackward.ui.analyzer.LogAnalyzerViewModel
import dev.stackward.ui.logs.LogsScreen
import dev.stackward.ui.logs.LogsViewModel
import dev.stackward.ui.onboarding.OnboardingViewModel
import dev.stackward.ui.onboarding.ProvisionStep
import dev.stackward.ui.settings.SettingsScreen
import dev.stackward.ui.settings.SettingsViewModel

private enum class AppDestination {
    ONBOARDING,
    LOGS,
    ANALYZER,
    SETTINGS,
}

@Composable
fun StackwardApp() {
    var destination by rememberSaveable { mutableStateOf<AppDestination?>(null) }
    var analyzerInitialLogs by remember { mutableStateOf<String?>(null) }
    val onboardingViewModel: OnboardingViewModel = viewModel()
    val logsViewModel: LogsViewModel = viewModel()
    val logAnalyzerViewModel: LogAnalyzerViewModel = viewModel()
    val settingsViewModel: SettingsViewModel = viewModel()
    val onboardingState by onboardingViewModel.uiState.collectAsState()

    if (destination == null) {
        destination = when {
            onboardingState.provisionedProfile != null ||
                onboardingState.step == ProvisionStep.SUCCESS -> AppDestination.LOGS
            else -> AppDestination.ONBOARDING
        }
    }

    LaunchedEffect(destination) {
        when (destination) {
            AppDestination.LOGS -> logsViewModel.reloadProfile()
            AppDestination.SETTINGS -> settingsViewModel.refresh()
            else -> Unit
        }
    }

    when (destination) {
        AppDestination.LOGS -> LogsScreen(
            viewModel = logsViewModel,
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
                onboardingViewModel.resetAfterRevoke()
                destination = AppDestination.ONBOARDING
            },
        )
        AppDestination.ONBOARDING -> OnboardingScreen(
            viewModel = onboardingViewModel,
            onProvisioned = { destination = AppDestination.LOGS },
        )
        null -> Unit
    }
}
