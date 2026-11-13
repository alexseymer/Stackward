package dev.stackward.check

import dev.stackward.connection.SshConnectionManager
import dev.stackward.onboarding.ServerProfile

/** Outcome of one check.sh run against a host. */
sealed class CheckRunResult {
    data class Success(val result: CheckResult) : CheckRunResult()
    data class Failure(val message: String) : CheckRunResult()
}

/**
 * Runs `~/.stackward/check.sh` on a profile's host over the existing SSH layer, and keeps the
 * host copy current: when the script is missing, or reports an older `version` than the one
 * bundled in the app, the bundled copy is installed (home directory only) and the check re-run.
 */
class CheckScriptRunner(
    private val ssh: SshConnectionManager,
    private val bundle: CheckScriptBundle,
) {

    suspend fun run(profile: ServerProfile): CheckRunResult {
        val first = runOnce(profile)
        val needsInstall = when (first) {
            is CheckRunResult.Success -> first.result.scriptVersion < bundle.version
            is CheckRunResult.Failure -> first.message.contains("No such file", ignoreCase = true)
        }
        if (!needsInstall) return first

        val installError = install(profile)
        if (installError != null) {
            // An older script that still works beats failing outright; a missing one cannot.
            return if (first is CheckRunResult.Success) first else CheckRunResult.Failure(installError)
        }
        return runOnce(profile)
    }

    /** Installs the bundled script; returns null on success, else a human-readable reason. */
    suspend fun install(profile: ServerProfile): String? = try {
        val output = ssh.execute(profile, bundle.installCommand())
        if (output.contains("STACKWARD_CHECK_INSTALLED=")) null
        else "check.sh install gave unexpected output: ${output.take(200)}"
    } catch (error: Exception) {
        "Could not install check.sh into ~/.stackward (${error.message ?: "SSH failed"}). " +
            "Run scripts/bootstrap_linux.sh on the host as an admin instead."
    }

    private suspend fun runOnce(profile: ServerProfile): CheckRunResult {
        return try {
            val output = ssh.execute(profile, COMMAND)
            CheckRunResult.Success(CheckScriptParser.parse(output))
        } catch (error: CheckScriptParseException) {
            CheckRunResult.Failure(error.message ?: "check.sh returned malformed JSON")
        } catch (error: Exception) {
            CheckRunResult.Failure(error.message ?: "SSH command failed")
        }
    }

    companion object {
        private const val COMMAND = "bash ~/.stackward/check.sh"
    }
}
