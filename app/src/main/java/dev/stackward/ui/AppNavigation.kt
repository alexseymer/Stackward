package dev.stackward.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.stackward.ui.logs.LogsScreen
import dev.stackward.ui.logs.LogsViewModel
import dev.stackward.ui.onboarding.OnboardingViewModel
import dev.stackward.ui.onboarding.ProvisionStep

private enum class AppDestination {
    ONBOARDING,
    LOGS,
}

@Composable
fun StackwardApp() {
    var destination by rememberSaveable { mutableStateOf<AppDestination?>(null) }
    val onboardingViewModel: OnboardingViewModel = viewModel()
    val logsViewModel: LogsViewModel = viewModel()
    val onboardingState by onboardingViewModel.uiState.collectAsState()

    if (destination == null) {
        destination = when {
            onboardingState.provisionedProfile != null ||
                onboardingState.step == ProvisionStep.SUCCESS -> AppDestination.LOGS
            else -> AppDestination.ONBOARDING
        }
    }

    LaunchedEffect(destination) {
        if (destination == AppDestination.LOGS) {
            logsViewModel.reloadProfile()
        }
    }

    when (destination) {
        AppDestination.LOGS -> LogsScreen(
            viewModel = logsViewModel,
            onOpenSettings = { destination = AppDestination.ONBOARDING },
        )
        AppDestination.ONBOARDING -> OnboardingScreen(
            viewModel = onboardingViewModel,
            onProvisioned = { destination = AppDestination.LOGS },
        )
        null -> Unit
    }
}
