package dev.stackward.crypto

import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * In-memory store for one-time bootstrap credentials (SSH password, pasted PEM).
 *
 * Values are AES-256-CBC encrypted with an ephemeral session key and are never
 * written to disk. [clear] wipes all slots — call after key install or on failure.
 */
class SessionSecretVault {

    enum class Slot {
        SSH_PASSWORD,
        PRIVATE_KEY_PEM,
        PRIVATE_KEY_PASSPHRASE,
    }

    private val sessionKey: ByteArray = ByteArray(KEY_BYTES).also { SecureRandom().nextBytes(it) }
    private val secrets = ConcurrentHashMap<Slot, ByteArray>()

    fun put(slot: Slot, value: String) {
        if (value.isEmpty()) {
            clear(slot)
            return
        }
        secrets[slot] = encrypt(value.toByteArray(Charsets.UTF_8))
    }

    fun get(slot: Slot): String? {
        val sealed = secrets[slot] ?: return null
        return runCatching {
            String(decrypt(sealed), Charsets.UTF_8)
        }.getOrNull()
    }

    fun has(slot: Slot): Boolean = get(slot)?.isNotBlank() == true

    fun clear(slot: Slot) {
        secrets.remove(slot)?.let(::zeroize)
    }

    fun clear() {
        secrets.keys.toList().forEach(::clear)
    }

    private fun encrypt(plaintext: ByteArray): ByteArray {
        val iv = ByteArray(IV_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(CIPHER)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec(), IvParameterSpec(iv))
        val ciphertext = cipher.doFinal(plaintext)
        return iv + ciphertext
    }

    private fun decrypt(sealed: ByteArray): ByteArray {
        require(sealed.size > IV_BYTES) { "Invalid sealed payload" }
        val iv = sealed.copyOfRange(0, IV_BYTES)
        val ciphertext = sealed.copyOfRange(IV_BYTES, sealed.size)
        val cipher = Cipher.getInstance(CIPHER)
        cipher.init(Cipher.DECRYPT_MODE, keySpec(), IvParameterSpec(iv))
        return cipher.doFinal(ciphertext)
    }

    private fun keySpec(): SecretKeySpec =
        SecretKeySpec(sessionKey.copyOf(), "AES")

    private fun zeroize(data: ByteArray) {
        data.fill(0)
    }

    companion object {
        private const val CIPHER = "AES/CBC/PKCS5Padding"
        private const val KEY_BYTES = 32
        private const val IV_BYTES = 16
    }
}
