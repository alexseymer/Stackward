package dev.stackward.inference

import android.app.ActivityManager
import android.content.Context

/**
 * Assesses device RAM to recommend E2B vs E4B model variants.
 */
class DeviceCapabilityChecker(
    private val context: Context,
) {

    fun assess(): DeviceCapability {
        val totalRamGb = (totalRamBytes() / (1024.0 * 1024.0 * 1024.0)).toInt().coerceAtLeast(1)
        val canRunE2B = totalRamGb >= ModelVariant.E2B.minimumRamGb
        val canRunE4B = totalRamGb >= ModelVariant.E4B.minimumRamGb
        val recommended = when {
            canRunE4B -> ModelVariant.E4B
            canRunE2B -> ModelVariant.E2B
            else -> ModelVariant.E2B
        }
        return DeviceCapability(
            totalRamGb = totalRamGb,
            recommendedVariant = recommended,
            canRunE2B = canRunE2B,
            canRunE4B = canRunE4B,
        )
    }

    private fun totalRamBytes(): Long {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        return memoryInfo.totalMem
    }
}
