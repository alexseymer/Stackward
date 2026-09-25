package dev.stackward.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.stackward.ui.dashboard.DashboardScreen
import dev.stackward.ui.dashboard.DashboardViewModel
import dev.stackward.ui.dashboard.HostDetailScreen
import dev.stackward.ui.dashboard.HostDetailViewModel
import dev.stackward.ui.logs.LogsScreen
import dev.stackward.ui.logs.LogsViewModel
import dev.stackward.ui.onboarding.OnboardingViewModel
import dev.stackward.ui.onboarding.ProvisionStep
import dev.stackward.ui.settings.SettingsScreen
import dev.stackward.ui.settings.SettingsViewModel

// Serializable so rememberSaveable can persist it across rotation/process death —
// a sealed class (unlike the enum this replaced) isn't Serializable by default.
private sealed class AppDestination : java.io.Serializable {
    data object Onboarding : AppDestination()
    data object Dashboard : AppDestination()
    data class HostDetail(val profileId: String) : AppDestination()
    data object Logs : AppDestination()
    data object Settings : AppDestination()
}

@Composable
fun StackwardApp() {
    var destination by rememberSaveable { mutableStateOf<AppDestination?>(null) }
    val onboardingViewModel: OnboardingViewModel = viewModel()
    val dashboardViewModel: DashboardViewModel = viewModel()
    val hostDetailViewModel: HostDetailViewModel = viewModel()
    val logsViewModel: LogsViewModel = viewModel()
    val settingsViewModel: SettingsViewModel = viewModel()
    val onboardingState by onboardingViewModel.uiState.collectAsState()

    if (destination == null) {
        destination = when {
            onboardingState.provisionedProfile != null ||
                onboardingState.step == ProvisionStep.SUCCESS -> AppDestination.Dashboard
            else -> AppDestination.Onboarding
        }
    }

    LaunchedEffect(destination) {
        when (val current = destination) {
            is AppDestination.Dashboard -> dashboardViewModel.refresh()
            is AppDestination.HostDetail -> hostDetailViewModel.load(current.profileId)
            is AppDestination.Logs -> logsViewModel.reloadProfile()
            is AppDestination.Settings -> settingsViewModel.refresh()
            else -> Unit
        }
    }

    when (val current = destination) {
        is AppDestination.Dashboard -> DashboardScreen(
            viewModel = dashboardViewModel,
            onOpenHost = { profileId -> destination = AppDestination.HostDetail(profileId) },
            onAddHost = {
                onboardingViewModel.startFresh()
                destination = AppDestination.Onboarding
            },
            onOpenLogs = { destination = AppDestination.Logs },
            onOpenSettings = { destination = AppDestination.Settings },
        )
        is AppDestination.HostDetail -> HostDetailScreen(
            viewModel = hostDetailViewModel,
            onBack = { destination = AppDestination.Dashboard },
        )
        is AppDestination.Logs -> LogsScreen(
            viewModel = logsViewModel,
            onOpenSettings = { destination = AppDestination.Settings },
            onBack = { destination = AppDestination.Dashboard },
        )
        is AppDestination.Settings -> SettingsScreen(
            viewModel = settingsViewModel,
            onBack = { destination = AppDestination.Dashboard },
            onPanicRevoked = {
                onboardingViewModel.resetAfterRevoke()
                destination = AppDestination.Onboarding
            },
        )
        is AppDestination.Onboarding -> OnboardingScreen(
            viewModel = onboardingViewModel,
            onProvisioned = { destination = AppDestination.Dashboard },
        )
        null -> Unit
    }
}
