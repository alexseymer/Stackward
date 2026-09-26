package dev.stackward.check

import dev.stackward.connection.SshConnectionManager
import dev.stackward.onboarding.ServerProfile

/** Outcome of one check.sh run against a host. */
sealed class CheckRunResult {
    data class Success(val result: CheckResult) : CheckRunResult()
    data class Failure(val message: String) : CheckRunResult()
}

/** Runs `~/.stackward/check.sh` on a profile's host over the existing SSH layer. */
class CheckScriptRunner(private val ssh: SshConnectionManager) {

    suspend fun run(profile: ServerProfile): CheckRunResult {
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
