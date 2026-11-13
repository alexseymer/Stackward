package dev.stackward.security

/**
 * Shell commands that edit the connected user's OWN `~/.ssh/authorized_keys`. The agent user owns
 * that file, so none of this needs root or sudo (PRD invariant 4). All values are single-quoted,
 * so a hostile key line or marker cannot break out of its argument.
 */
object AuthorizedKeysCommands {

    private const val FILE = "~/.ssh/authorized_keys"

    /** Appends [publicKeyLine] unless it is already present. Prints `key pushed` / `key already present`. */
    fun pushKey(publicKeyLine: String): String {
        val key = quote(publicKeyLine)
        return "umask 077 && mkdir -p ~/.ssh && chmod 700 ~/.ssh && touch $FILE && chmod 600 $FILE && " +
            "{ if grep -qF -- $key $FILE; then echo key already present; " +
            "else printf '%s\\n' $key >> $FILE && echo key pushed; fi; }"
    }

    /** Removes every line containing [marker] (the base64 key body). Prints `key revoked`. */
    fun revokeKey(marker: String): String {
        require(marker.isNotBlank()) { "Revoke marker must not be blank" }
        val m = quote(marker)
        return "umask 077 && if [ -f $FILE ]; then " +
            "grep -vF -- $m $FILE > $FILE.tmp; mv $FILE.tmp $FILE && chmod 600 $FILE; fi; echo key revoked"
    }

    /** Empties authorized_keys (panic revoke). Prints `all agent keys revoked`. */
    fun revokeAll(): String =
        "umask 077 && mkdir -p ~/.ssh && chmod 700 ~/.ssh && : > $FILE && chmod 600 $FILE && echo all agent keys revoked"

    private fun quote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"
}
