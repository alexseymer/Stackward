package dev.stackward.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Ephemeral AES-256-GCM vault for bootstrap secrets.
 *
 * Ciphertext is held only in process memory. The wrapping key lives in
 * Android Keystore. Secrets are never written to disk / SharedPreferences.
 */
class SessionSecretVault {

    enum class Slot {
        SSH_PASSWORD,
        PRIVATE_KEY_PEM,
        PRIVATE_KEY_PASSPHRASE,
    }

    private val keyStore: KeyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
    private val sealed = mutableMapOf<Slot, ByteArray>()

    fun put(slot: Slot, plaintext: String) {
        if (plaintext.isEmpty()) {
            clear(slot)
            return
        }
        val previous = sealed.put(slot, encrypt(plaintext.toByteArray(Charsets.UTF_8)))
        previous?.fill(0)
    }

    fun get(slot: Slot): String? {
        val blob = sealed[slot] ?: return null
        val plain = decrypt(blob)
        return try {
            String(plain, Charsets.UTF_8)
        } finally {
            plain.fill(0)
        }
    }

    fun has(slot: Slot): Boolean = sealed.containsKey(slot)

    fun clear(slot: Slot? = null) {
        if (slot == null) {
            sealed.values.forEach { it.fill(0) }
            sealed.clear()
            return
        }
        sealed.remove(slot)?.fill(0)
    }

    private fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext)
        return ByteBuffer.allocate(4 + iv.size + ciphertext.size)
            .putInt(iv.size)
            .put(iv)
            .put(ciphertext)
            .array()
    }

    private fun decrypt(blob: ByteArray): ByteArray {
        val buffer = ByteBuffer.wrap(blob)
        val ivSize = buffer.int
        require(ivSize in 12..32) { "Invalid IV size" }
        val iv = ByteArray(ivSize)
        buffer.get(iv)
        val ciphertext = ByteArray(buffer.remaining())
        buffer.get(ciphertext)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    private fun getOrCreateKey(): SecretKey {
        if (keyStore.containsAlias(KEY_ALIAS)) {
            val entry = keyStore.getEntry(KEY_ALIAS, null) as KeyStore.SecretKeyEntry
            return entry.secretKey
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS = "stackward_session_secret_aes"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
    }
}
