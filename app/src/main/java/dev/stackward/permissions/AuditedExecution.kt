package dev.stackward.permissions

/**
 * Runs [executor], records an [AuditEntry] for the outcome, and returns the result.
 *
 * This mirrors the audit-on-success-or-failure shape [PermissionExecutor]'s internal
 * `runRemote` already uses for model-proposed [ActionProposal]s — deliberately kept as a
 * separate, tier-gating-free helper rather than merged into `PermissionExecutor` itself.
 * Callers here (currently check.sh suggestion execution, see
 * `dev.stackward.ui.dashboard.HostDetailViewModel`) have already been classified by their
 * own allowlist (e.g. `CheckActionCatalog`) before reaching this point; routing them through
 * `PermissionExecutor.executeApproved` would additionally re-run `PermissionEngine.evaluate`,
 * which denies anything above [PermissionTier.ROUTINE] under the v1 Monitor-only capability
 * pack — that would silently break the risky/biometric-confirm path this helper exists for.
 */
suspend fun AuditLogRepository.recordAuditedExecution(
    tier: PermissionTier,
    command: String,
    reason: String?,
    executor: suspend () -> String,
): Result<String> {
    return try {
        val output = executor()
        append(AuditEntry(System.currentTimeMillis(), tier, command, approved = true, output = output, reason = reason))
        Result.success(output)
    } catch (error: Exception) {
        append(
            AuditEntry(
                System.currentTimeMillis(),
                tier,
                command,
                approved = false,
                output = error.message,
                reason = reason,
            ),
        )
        Result.failure(error)
    }
}
