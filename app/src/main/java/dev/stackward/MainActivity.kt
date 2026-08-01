package dev.stackward

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import dev.stackward.onboarding.OnboardingFlow

/**
 * Entry point. Routes to [OnboardingFlow] until at least one host is provisioned.
 */
class MainActivity : AppCompatActivity() {

    private val onboardingFlow = OnboardingFlow()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // TODO: setContentView with Compose or XML layout
        // TODO: if no hosts provisioned → onboardingFlow.start()
        // TODO: else → main dashboard (log digest, query, settings)
    }
}
