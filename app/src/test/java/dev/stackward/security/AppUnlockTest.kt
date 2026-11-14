package dev.stackward.security

import dev.stackward.crypto.KeyAccess
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUnlockTest {

    private class FakeKeys(var passwordKey: Boolean, val password: String = "right password") : KeyAccess {
        var unlocked = false
        var biometricMarks = 0
        override fun isUnlocked(alias: String) = unlocked
        override fun isPasswordKey(alias: String) = passwordKey
        override fun unlockWithPassword(alias: String, password: CharArray): Boolean =
            (String(password) == this.password).also { if (it) unlocked = true }
        override fun markBiometricAuthorized() { biometricMarks++; unlocked = true }
    }

    private class FakeGate(var allow: Boolean = true) : UserPresenceGate {
        var prompts = 0
        override suspend fun authenticate(title: String, subtitle: String): Boolean { prompts++; return allow }
    }

    private fun unlock(keys: FakeKeys) = AppUnlock(keys) { "alias" }

    @Test
    fun oneFingerprintCoversTheWholeBatch() = runBlocking {
        val keys = FakeKeys(passwordKey = false)
        val gate = FakeGate()
        val unlock = unlock(keys)

        // "Check all" over three hosts: the first call prompts, the rest find the key already unlocked.
        repeat(3) { assertTrue(unlock.ensureUnlocked(gate, "t", "s")) }

        assertEquals(1, gate.prompts)
        assertEquals(1, keys.biometricMarks)
    }

    @Test
    fun expiredWindow_promptsAgain() = runBlocking {
        val keys = FakeKeys(passwordKey = false)
        val gate = FakeGate()
        val unlock = unlock(keys)

        unlock.ensureUnlocked(gate, "t", "s")
        keys.unlocked = false // window ran out
        unlock.ensureUnlocked(gate, "t", "s")

        assertEquals(2, gate.prompts)
    }

    @Test
    fun cancelledBiometric_isNotUnlocked() = runBlocking {
        val keys = FakeKeys(passwordKey = false)
        assertFalse(unlock(keys).ensureUnlocked(FakeGate(allow = false), "t", "s"))
        assertEquals(0, keys.biometricMarks)
    }

    @Test
    fun passwordKey_neverUsesTheBiometricGate_andRetriesAfterAWrongPassword() = runBlocking {
        val keys = FakeKeys(passwordKey = true)
        val gate = FakeGate()
        val unlock = unlock(keys)

        val result = async { unlock.ensureUnlocked(gate, "t", "s") }
        val first = unlock.request.filterNotNull().first()
        assertFalse(first.wrong)
        first.submit("nope".toCharArray())
        // The dialog is shown again, flagged as wrong.
        val second = unlock.request.filterNotNull().first { it !== first }
        assertTrue(second.wrong)
        second.submit("right password".toCharArray())

        assertTrue(result.await())
        assertEquals(0, gate.prompts)
    }

    @Test
    fun passwordKey_cancelledDialog_isNotUnlocked() = runBlocking {
        val keys = FakeKeys(passwordKey = true)
        val unlock = unlock(keys)
        val result = async { unlock.ensureUnlocked(FakeGate(), "t", "s") }
        unlock.request.filterNotNull().first().cancel()
        assertFalse(result.await())
    }

    @Test
    fun newPasswordRequest_isACreateDialog() = runBlocking {
        val unlock = unlock(FakeKeys(passwordKey = false))
        val result = async { unlock.askNewPassword() }
        val request = unlock.request.filterNotNull().first()
        assertEquals(PasswordRequest.Mode.CREATE, request.mode)
        request.submit("a long enough pw".toCharArray())
        assertEquals("a long enough pw", String(result.await()!!))
    }
}
