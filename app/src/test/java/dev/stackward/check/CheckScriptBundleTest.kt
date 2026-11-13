package dev.stackward.check

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CheckScriptBundleTest {

    @Test
    fun versionOf_readsHeader() {
        assertEquals(7, CheckScriptBundle.versionOf("#!/usr/bin/env bash\n# stackward-check-version: 7\necho hi\n"))
    }

    @Test
    fun versionOf_missingHeaderIsZero() {
        assertEquals(0, CheckScriptBundle.versionOf("#!/usr/bin/env bash\necho hi\n"))
    }

    @Test
    fun installCommand_carriesScriptAsBase64AndDoesNotNeedRoot() {
        val script = "#!/bin/bash\n# stackward-check-version: 3\necho \"it's \$HOME\"\n"
        val command = CheckScriptBundle.installCommand(script)

        val encoded = Regex("printf '%s' '([A-Za-z0-9+/=]+)'").find(command)!!.groupValues[1]
        assertEquals(script, String(Base64.getDecoder().decode(encoded), Charsets.UTF_8))
        assertTrue(command.endsWith("echo STACKWARD_CHECK_INSTALLED=3"))
        assertFalse(command.contains("sudo"))
        assertTrue(command.contains("chmod 700"))
    }

    @Test
    fun installCommand_scriptTextNeverAppearsUnencodedInTheCommand() {
        val command = CheckScriptBundle.installCommand("echo \"; rm -rf / #\"")
        assertFalse(command.contains("rm -rf"))
    }
}
