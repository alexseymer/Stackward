package dev.stackward.permissions

/**
 * Runs [executor], records an [AuditEntry] for the outcome, and returns the result.
 *
 * Callers (currently check.sh suggestion execution, see
 * `dev.stackward.ui.dashboard.HostDetailViewModel`) have already been classified by their
 * own allowlist (`CheckActionCatalog`) before reaching this point. Deleted together with
 * the catalog in PRD §11 R2.
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
