package dev.stackward.permissions

/**
 * User-selected scope for what the on-device model may propose.
 * Tier 2/3 human confirmation gates are unchanged — packs only limit proposals.
 */
enum class CapabilityPack(val displayName: String, val summary: String) {
    MONITOR(
        displayName = "Monitor",
        summary = "Read-only logs, status, and digests (Tier 1)",
    ),
    MAINTAIN(
        displayName = "Maintain",
        summary = "Monitor + one-time maintenance actions (Tier 2)",
    ),
    PROVISION(
        displayName = "Provision",
        summary = "Not available in v1 — future broader proposals, still human-gated",
    ),
}
