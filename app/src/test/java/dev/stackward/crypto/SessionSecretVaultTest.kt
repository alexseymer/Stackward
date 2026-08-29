package dev.stackward.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionSecretVaultTest {

    @Test
    fun putGetAndHas_roundTrip() {
        val vault = SessionSecretVault()
        assertFalse(vault.has(SessionSecretVault.Slot.SSH_PASSWORD))

        vault.put(SessionSecretVault.Slot.SSH_PASSWORD, "secret")
        assertTrue(vault.has(SessionSecretVault.Slot.SSH_PASSWORD))
        assertEquals("secret", vault.get(SessionSecretVault.Slot.SSH_PASSWORD))
    }

    @Test
    fun clearSlot_removesSecret() {
        val vault = SessionSecretVault()
        vault.put(SessionSecretVault.Slot.PRIVATE_KEY_PEM, "-----BEGIN PRIVATE KEY-----")
        vault.clear(SessionSecretVault.Slot.PRIVATE_KEY_PEM)
        assertNull(vault.get(SessionSecretVault.Slot.PRIVATE_KEY_PEM))
    }

    @Test
    fun clear_wipesAllSlots() {
        val vault = SessionSecretVault()
        vault.put(SessionSecretVault.Slot.SSH_PASSWORD, "pw")
        vault.put(SessionSecretVault.Slot.PRIVATE_KEY_PASSPHRASE, "phrase")
        vault.clear()
        assertFalse(vault.has(SessionSecretVault.Slot.SSH_PASSWORD))
        assertFalse(vault.has(SessionSecretVault.Slot.PRIVATE_KEY_PASSPHRASE))
    }

    @Test
    fun putEmpty_clearsSlot() {
        val vault = SessionSecretVault()
        vault.put(SessionSecretVault.Slot.SSH_PASSWORD, "pw")
        vault.put(SessionSecretVault.Slot.SSH_PASSWORD, "")
        assertFalse(vault.has(SessionSecretVault.Slot.SSH_PASSWORD))
    }
}
