package dev.stackward.crypto

import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Seals a private key under a user password: PBKDF2-HMAC-SHA256 -> AES-256-GCM. Used for phones that
 * cannot make a biometric-bound Keystore key (PRD Q2): the key at rest is useless without the password.
 */
object PasswordKeyCrypto {

    const val ITERATIONS = 210_000
    const val MIN_PASSWORD_LENGTH = 10
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    class Sealed(val salt: ByteArray, val iv: ByteArray, val ciphertext: ByteArray, val iterations: Int)

    fun isAcceptable(password: CharArray): Boolean = password.size >= MIN_PASSWORD_LENGTH

    fun seal(
        plain: ByteArray,
        password: CharArray,
        iterations: Int = ITERATIONS,
        random: SecureRandom = SecureRandom(),
    ): Sealed {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(password, salt, iterations), GCMParameterSpec(TAG_BITS, iv))
        return Sealed(salt, iv, cipher.doFinal(plain), iterations)
    }

    /** Returns the plaintext, or null when [password] is wrong (or the data was tampered with). */
    fun open(sealed: Sealed, password: CharArray): ByteArray? = try {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            deriveKey(password, sealed.salt, sealed.iterations),
            GCMParameterSpec(TAG_BITS, sealed.iv),
        )
        cipher.doFinal(sealed.ciphertext)
    } catch (_: AEADBadTagException) {
        null
    }

    private fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, iterations, 256)
        try {
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return SecretKeySpec(bytes, "AES")
        } finally {
            spec.clearPassword()
        }
    }
}
