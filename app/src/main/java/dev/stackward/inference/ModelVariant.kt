package dev.stackward.inference

/**
 * Recommended on-device model variant (Gemma 4 E2B/E4B effective size).
 */
enum class ModelVariant(
    val displayName: String,
    val minimumRamGb: Int,
    val fileHint: String,
) {
    E2B(
        displayName = "Gemma E2B (~2B effective)",
        minimumRamGb = 4,
        fileHint = "gemma-*-e2b*.task or *.litertlm",
    ),
    E4B(
        displayName = "Gemma E4B (~4B effective)",
        minimumRamGb = 6,
        fileHint = "gemma-*-e4b*.task or *.litertlm",
    ),
}

data class DeviceCapability(
    val totalRamGb: Int,
    val recommendedVariant: ModelVariant,
    val canRunE2B: Boolean,
    val canRunE4B: Boolean,
)
