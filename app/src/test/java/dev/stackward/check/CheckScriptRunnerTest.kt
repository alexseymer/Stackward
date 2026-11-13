package dev.stackward.check

import dev.stackward.connection.SshException
import dev.stackward.onboarding.HostType
import dev.stackward.onboarding.ServerProfile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CheckScriptRunnerTest {

    private val profile = ServerProfile(id = "p", host = "h", hostType = HostType.PLAIN_LINUX, hostKeyFingerprint = "fp")

    private class Source(override val version: Int) : CheckScriptSource {
        override fun installCommand() = "INSTALL"
    }

    private fun json(version: Int) =
        """{"version":$version,"timestamp":"t","hostname":"h","issues":[],"coverage":{"journal":"ok","docker":"ok","systemd":"ok"}}"""

    /** Scripted host: responds to the check command and the install command separately. */
    private class FakeHost(
        var checkResponses: MutableList<() -> String>,
        var installResponse: () -> String = { "STACKWARD_CHECK_INSTALLED=2" },
    ) {
        val commands = mutableListOf<String>()
        val executor = RemoteCommandExecutor { _, command ->
            commands += command
            if (command == "INSTALL") installResponse() else checkResponses.removeFirst()()
        }
    }

    private fun run(host: FakeHost, bundleVersion: Int = 2) =
        runBlocking { CheckScriptRunner(host.executor, Source(bundleVersion)).run(profile) }

    @Test
    fun currentScript_runsOnceWithoutInstalling() {
        val host = FakeHost(mutableListOf({ json(2) }))
        val result = run(host)

        assertTrue(result is CheckRunResult.Success)
        assertEquals(1, host.commands.size)
    }

    @Test
    fun missingScript_isInstalledThenRerun() {
        val host = FakeHost(mutableListOf({ throw SshException("bash: /home/a/.stackward/check.sh: No such file or directory") }, { json(2) }))
        val result = run(host)

        assertTrue(result is CheckRunResult.Success)
        assertEquals(listOf("bash ~/.stackward/check.sh", "INSTALL", "bash ~/.stackward/check.sh"), host.commands)
    }

    @Test
    fun olderScript_isRefreshedThenRerun() {
        val host = FakeHost(mutableListOf({ json(1) }, { json(2) }))
        val result = run(host) as CheckRunResult.Success

        assertEquals(2, result.result.scriptVersion)
        assertEquals(3, host.commands.size)
    }

    @Test
    fun installFailure_withWorkingOlderScript_keepsTheOlderResult() {
        val host = FakeHost(mutableListOf({ json(1) }), installResponse = { throw SshException("read-only file system") })
        val result = run(host)

        assertTrue(result is CheckRunResult.Success)
        assertEquals(1, (result as CheckRunResult.Success).result.scriptVersion)
    }

    @Test
    fun installFailure_withNoScript_explainsHowToFix() {
        val host = FakeHost(
            mutableListOf({ throw SshException("No such file or directory") }),
            installResponse = { throw SshException("read-only file system") },
        )
        val failure = run(host) as CheckRunResult.Failure

        assertTrue(failure.message.contains("read-only file system"))
        assertTrue(failure.message.contains("bootstrap_linux.sh"))
    }

    @Test
    fun unrelatedSshFailure_doesNotTriggerInstall() {
        val host = FakeHost(mutableListOf({ throw SshException("Connection refused") }))
        val failure = run(host) as CheckRunResult.Failure

        assertEquals("Connection refused", failure.message)
        assertEquals(1, host.commands.size)
    }

    @Test
    fun malformedOutput_isAFailureNotAnInstall() {
        val host = FakeHost(mutableListOf({ "not json" }))
        assertTrue(run(host) is CheckRunResult.Failure)
        assertEquals(1, host.commands.size)
    }
}
