package dev.stackward.check

/** Risk as classified by *this device*, never by the host's self-reported string. */
enum class ActionRisk {
    SAFE,
    RISKY,
    SCARY,
    /** check.sh proposed an action id this catalog doesn't recognize. */
    UNKNOWN,
}

/**
 * [command] is the literal command this device will run for [ActionRisk.SAFE]/[RISKY]
 * actions — never anything check.sh sends over the wire. Null for [ActionRisk.SCARY]
 * and [ActionRisk.UNKNOWN], which are never executed.
 */
data class CatalogEntry(
    val risk: ActionRisk,
    val command: String?,
    val description: String,
)

/**
 * Phone-side allowlist mapping a check.sh suggestion's `action` id to a literal
 * command and a risk this device trusts. See PRD.md §5.1 risk table and the
 * "Check script is compromised" row of PRD.md §8: a suggestion's own `risk` field
 * is display-only — a compromised or buggy check.sh cannot get an unreviewed
 * action past this gate by mislabeling it "safe" downstream in
 * [CheckSuggestionGate]. Keep in sync with `scripts/check.sh`'s
 * `detect_suggestions()` — every id it can emit must have an entry here.
 */
object CheckActionCatalog {

    private val entries: Map<String, CatalogEntry> = mapOf(
        "tail_journal" to CatalogEntry(
            risk = ActionRisk.SAFE,
            command = "journalctl -n 100 --no-pager",
            description = "Show the last 100 journal lines",
        ),
        "list_failed_services" to CatalogEntry(
            risk = ActionRisk.SAFE,
            command = "systemctl list-units --state=failed --no-pager --plain",
            description = "List failed systemd units",
        ),
        "cleanup_old_logs" to CatalogEntry(
            risk = ActionRisk.RISKY,
            command = "journalctl --vacuum-time=7d",
            description = "Vacuum journal entries older than 7 days to free disk space",
        ),
        "disable_ssh_password_auth" to CatalogEntry(
            risk = ActionRisk.SCARY,
            command = null,
            description = "At the console (not over the connection you're about to " +
                "disable): edit /etc/ssh/sshd_config, set 'PasswordAuthentication no', " +
                "then run 'sudo systemctl restart sshd'.",
        ),
    )

    fun lookup(actionId: String): CatalogEntry = entries[actionId] ?: CatalogEntry(
        risk = ActionRisk.UNKNOWN,
        command = null,
        description = "Unrecognized action \"$actionId\" — not in this device's catalog. " +
            "Review manually; unknown actions are never executed, regardless of what " +
            "the host reports.",
    )
}
