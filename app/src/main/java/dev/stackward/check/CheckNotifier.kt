package dev.stackward.check

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.stackward.R
import dev.stackward.onboarding.ServerProfile

/**
 * Critical-issue-only notifications — see STRATEGY.md "Notifications: Critical issues
 * only (service down, disk >95%, security risk detected)". Safe entirely without the
 * POST_NOTIFICATIONS runtime permission (Android 13+): [notifyIfCritical] just becomes a
 * no-op, the same way the rest of the app degrades when an optional capability is missing.
 */
object CheckNotifier {

    private const val CHANNEL_ID = "stackward_critical_issues"
    private var channelCreated = false

    /**
     * No-ops unless [result] contains at least one CRITICAL-severity issue AND the
     * POST_NOTIFICATIONS permission is granted (or the device predates that requirement).
     * The permission check is inlined right before [NotificationManagerCompat.notify] —
     * Android Lint's MissingPermission check (an error-severity default, would break
     * :app:lintDebug) only reliably recognizes this exact adjacent-check-then-call shape,
     * not a check hidden behind a separate helper function.
     */
    fun notifyIfCritical(context: Context, profile: ServerProfile, result: CheckResult) {
        if (result.maxSeverity() != IssueSeverity.CRITICAL) return

        ensureChannel(context)
        val criticalCount = result.issues.count { IssueSeverity.fromString(it.severity) == IssueSeverity.CRITICAL }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("${profile.host}: critical issue detected")
            .setContentText("$criticalCount critical issue(s) — open Stackward to review")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            NotificationManagerCompat.from(context).notify(profile.id.hashCode(), notification)
        }
    }

    private fun ensureChannel(context: Context) {
        if (channelCreated) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Critical infrastructure issues",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Alerts when check.sh finds a critical issue on a monitored host"
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        channelCreated = true
    }
}
