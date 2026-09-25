package dev.stackward.permissions

/**
 * v1 scope: Monitor tier only. Risk-based gating (safe/risky/scary) replaces tier selection.
 * MONITOR is the only pack available in v1; future phases will add MAINTAIN (with biometric-gated
 * risky actions) and PROVISION (scary actions forbidden, manual workarounds documented).
 */
enum class CapabilityPack(val displayName: String, val summary: String) {
    MONITOR(
        displayName = "Monitor",
        summary = "Read-only anomaly detection and structured checks",
    ),
}
