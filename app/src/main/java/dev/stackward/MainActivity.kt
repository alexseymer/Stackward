package dev.stackward

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.stackward.ui.OnboardingScreen
import dev.stackward.ui.StackwardTheme
import dev.stackward.ui.onboarding.OnboardingViewModel

/**
 * Entry point. Shows onboarding until at least one host is provisioned.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            StackwardTheme {
                val viewModel: OnboardingViewModel = viewModel()
                OnboardingScreen(viewModel = viewModel)
            }
        }
    }
}
