package dev.stackward.security

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/** Runs the real commands under `sh` with a throwaway HOME, so quoting and file handling are exercised. */
class AuthorizedKeysCommandsTest {

    private lateinit var home: File
    private val keyA = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA a@phone-one"
    private val keyB = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB b@phone-two"

    @Before
    fun setUp() {
        assumeTrue(File("/bin/sh").exists())
        home = Files.createTempDirectory("stackward-home").toFile()
    }

    private fun sh(command: String): String {
        val process = ProcessBuilder("sh", "-c", command)
            .redirectErrorStream(true)
            .apply { environment()["HOME"] = home.absolutePath }
            .start()
        val out = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(10, TimeUnit.SECONDS))
        assertEquals(out, 0, process.exitValue())
        return out.trim()
    }

    private fun authorizedKeys() = File(home, ".ssh/authorized_keys").readLines()

    @Test
    fun commandsNeverUseSudo() {
        listOf(
            AuthorizedKeysCommands.pushKey(keyA),
            AuthorizedKeysCommands.revokeKey("AAAA"),
            AuthorizedKeysCommands.revokeAll(),
        ).forEach { assertFalse(it, it.contains("sudo")) }
    }

    @Test
    fun pushKey_createsFileWithOwnerOnlyModeAndIsIdempotent() {
        assertEquals("key pushed", sh(AuthorizedKeysCommands.pushKey(keyA)))
        assertEquals("key already present", sh(AuthorizedKeysCommands.pushKey(keyA)))

        assertEquals(listOf(keyA), authorizedKeys())
        assertEquals("rw-------", java.nio.file.Files.getPosixFilePermissions(File(home, ".ssh/authorized_keys").toPath())
            .let { perms -> java.nio.file.attribute.PosixFilePermissions.toString(perms) })
    }

    @Test
    fun revokeKey_removesOnlyTheMatchingLine() {
        sh(AuthorizedKeysCommands.pushKey(keyA))
        sh(AuthorizedKeysCommands.pushKey(keyB))

        assertEquals("key revoked", sh(AuthorizedKeysCommands.revokeKey("a@phone-one")))

        assertEquals(listOf(keyB), authorizedKeys())
    }

    @Test
    fun revokeKey_canRemoveTheLastKeyAndToleratesMissingFile() {
        assertEquals("key revoked", sh(AuthorizedKeysCommands.revokeKey("whatever")))
        sh(AuthorizedKeysCommands.pushKey(keyA))
        sh(AuthorizedKeysCommands.revokeKey("a@phone-one"))
        assertEquals(emptyList<String>(), authorizedKeys())
    }

    @Test
    fun revokeAll_emptiesTheFile() {
        sh(AuthorizedKeysCommands.pushKey(keyA))
        assertEquals("all agent keys revoked", sh(AuthorizedKeysCommands.revokeAll()))
        assertEquals(0L, File(home, ".ssh/authorized_keys").length())
    }

    @Test
    fun hostileValuesStayInsideTheirArgument() {
        val marker = "x'; touch \"${'$'}HOME/pwned\"; echo '"
        sh(AuthorizedKeysCommands.revokeKey(marker))
        sh(AuthorizedKeysCommands.pushKey("ssh-ed25519 A'; touch \"${'$'}HOME/pwned2\"; echo '"))

        assertFalse(File(home, "pwned").exists())
        assertFalse(File(home, "pwned2").exists())
    }
}
