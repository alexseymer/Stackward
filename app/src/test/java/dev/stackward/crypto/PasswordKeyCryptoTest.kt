package dev.stackward.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PasswordKeyCryptoTest {
    // Few iterations: the tests check behaviour, not work factor.
    private val fast = 1_000
    private val secret = ByteArray(48) { it.toByte() }

    @Test
    fun roundTrip_withTheRightPassword() {
        val sealed = PasswordKeyCrypto.seal(secret, "correct horse battery".toCharArray(), fast)
        assertArrayEquals(secret, PasswordKeyCrypto.open(sealed, "correct horse battery".toCharArray()))
    }

    @Test
    fun wrongPassword_yieldsNullNotGarbage() {
        val sealed = PasswordKeyCrypto.seal(secret, "correct horse battery".toCharArray(), fast)
        assertNull(PasswordKeyCrypto.open(sealed, "wrong password!!".toCharArray()))
    }

    @Test
    fun tamperedCiphertext_isRejected() {
        val sealed = PasswordKeyCrypto.seal(secret, "correct horse battery".toCharArray(), fast)
        sealed.ciphertext[0] = (sealed.ciphertext[0].toInt() xor 1).toByte()
        assertNull(PasswordKeyCrypto.open(sealed, "correct horse battery".toCharArray()))
    }

    @Test
    fun ciphertextDoesNotContainThePlaintext_andSaltsDiffer() {
        val a = PasswordKeyCrypto.seal(secret, "correct horse battery".toCharArray(), fast)
        val b = PasswordKeyCrypto.seal(secret, "correct horse battery".toCharArray(), fast)
        assertFalse(a.ciphertext.toList().windowed(secret.size).any { it == secret.toList() })
        assertNotEquals(a.salt.toList(), b.salt.toList())
        assertNotEquals(a.ciphertext.toList(), b.ciphertext.toList())
    }

    @Test
    fun minimumLengthIsEnforcedByIsAcceptable() {
        assertFalse(PasswordKeyCrypto.isAcceptable("short".toCharArray()))
        assertTrue(PasswordKeyCrypto.isAcceptable("long enough pw".toCharArray()))
    }
}
