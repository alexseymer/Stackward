package dev.stackward

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import dev.stackward.ui.StackwardApp
import dev.stackward.ui.StackwardTheme

/**
 * Must be a [FragmentActivity] — BiometricPrompt / [dev.stackward.ui.security.BiometricGate]
 * require fragment support; casting [androidx.activity.ComponentActivity] crashes on launch.
 */
class MainActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            StackwardTheme {
                StackwardApp()
            }
        }
    }
}
