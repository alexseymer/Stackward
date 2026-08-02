package dev.stackward.crypto

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dev.stackward.crypto.SecurePrefs
import net.schmizz.sshj.common.SecurityUtils
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64 as JavaBase64

/**
 * Manages the agent's ed25519 SSH keypair in Android Keystore.
 *
 * API 33+: hardware-backed Ed25519 in Android Keystore with biometric gate.
 * API 28–32: Ed25519 key encrypted in EncryptedSharedPreferences (software fallback).
 *
 * The private key never leaves secure storage. Biometric data never leaves the device.
 */
class AgentKeyManager(
    private val context: Context,
) {

    private val keyStore: KeyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }

  fun generateKeypair(alias: String = KEY_ALIAS, replaceExisting: Boolean = true): KeyPair {
        if (replaceExisting) {
            deleteKeypair(alias)
        } else if (hasKeypair(alias)) {
            throw IllegalStateException("Keypair already exists for alias: $alias")
        }

        if (supportsKeystoreEd25519()) {
            try {
                return generateKeystoreKeypair(alias)
            } catch (error: Exception) {
                // Fall back so onboarding still works if Keystore Ed25519 is unavailable.
                android.util.Log.w(TAG, "Keystore Ed25519 failed, using software key", error)
                deleteKeypair(alias)
            }
        }
        return generateSoftwareKeypair(alias)
    }

    fun getPublicKeyOpenSSH(alias: String = KEY_ALIAS): String {
        val publicKey = getPublicKey(alias)
            ?: throw IllegalStateException("No keypair found for alias: $alias")
        return OpenSshPublicKeyEncoder.encode(publicKey)
    }

    fun getKeyPair(alias: String = KEY_ALIAS): KeyPair? {
        if (!hasKeypair(alias)) return null

        return if (supportsKeystoreEd25519() && keyStore.containsAlias(alias)) {
            val entry = keyStore.getEntry(alias, null) as KeyStore.PrivateKeyEntry
            KeyPair(entry.certificate.publicKey, entry.privateKey)
        } else {
            loadSoftwareKeypair(alias)
        }
    }

    fun sign(data: ByteArray, alias: String = KEY_ALIAS): ByteArray {
        val privateKey = getKeyPair(alias)?.private
            ?: throw IllegalStateException("No keypair found for alias: $alias")

        val signature = createEd25519Signature()
        signature.initSign(privateKey)
        signature.update(data)
        return signature.sign()
    }

    fun hasKeypair(alias: String = KEY_ALIAS): Boolean {
        if (supportsKeystoreEd25519() && keyStore.containsAlias(alias)) {
            return true
        }
        // Only touch software prefs when Keystore Ed25519 is unavailable (API < 33)
        // or when checking for a legacy software key on devices that upgraded.
        return if (!supportsKeystoreEd25519()) {
            softwarePrefs().contains(prefKey(alias, SUFFIX_PUBLIC))
        } else {
            runCatching { softwarePrefs().contains(prefKey(alias, SUFFIX_PUBLIC)) }.getOrDefault(false)
        }
    }

    fun deleteKeypair(alias: String = KEY_ALIAS) {
        if (keyStore.containsAlias(alias)) {
            keyStore.deleteEntry(alias)
        }
        softwarePrefs().edit()
            .remove(prefKey(alias, SUFFIX_PUBLIC))
            .remove(prefKey(alias, SUFFIX_PRIVATE))
            .apply()
    }

    fun usesHardwareKeystore(): Boolean {
        return supportsKeystoreEd25519() && keyStore.containsAlias(KEY_ALIAS)
    }

    private fun getPublicKey(alias: String): PublicKey? {
        return getKeyPair(alias)?.public
    }

    private fun generateKeystoreKeypair(alias: String): KeyPair {
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
        ).apply {
            // Android Keystore Ed25519 uses EC + ECGenParameterSpec("ed25519")
            // (not EdECGenParameterSpec — that class is not on Android).
            setAlgorithmParameterSpec(ECGenParameterSpec("ed25519"))
            setDigests(KeyProperties.DIGEST_NONE)
            setUserAuthenticationRequired(true)
            setInvalidatedByBiometricEnrollment(true)
            // -1 = every use requires authentication (API 30+)
            setUserAuthenticationValidityDurationSeconds(-1)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                setUnlockedDeviceRequired(true)
            }
        }.build()

        val generator = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC,
            KEYSTORE_PROVIDER,
        )
        generator.initialize(spec)
        return generator.generateKeyPair()
    }

    private fun generateSoftwareKeypair(alias: String): KeyPair {
        val keyPair = SecurityUtils.getKeyPairGenerator("Ed25519").generateKeyPair()
        persistSoftwareKeypair(alias, keyPair)
        return keyPair
    }

    private fun persistSoftwareKeypair(alias: String, keyPair: KeyPair) {
        val publicB64 = JavaBase64.getEncoder().encodeToString(keyPair.public.encoded)
        val privateB64 = JavaBase64.getEncoder().encodeToString(keyPair.private.encoded)

        softwarePrefs().edit()
            .putString(prefKey(alias, SUFFIX_PUBLIC), publicB64)
            .putString(prefKey(alias, SUFFIX_PRIVATE), privateB64)
            .apply()
    }

    private fun loadSoftwareKeypair(alias: String): KeyPair? {
        val prefs = softwarePrefs()
        val publicB64 = prefs.getString(prefKey(alias, SUFFIX_PUBLIC), null) ?: return null
        val privateB64 = prefs.getString(prefKey(alias, SUFFIX_PRIVATE), null) ?: return null

        val keyFactory = SecurityUtils.getKeyFactory("Ed25519")
        val publicKey = keyFactory.generatePublic(
            X509EncodedKeySpec(JavaBase64.getDecoder().decode(publicB64))
        )
        val privateKey = keyFactory.generatePrivate(
            PKCS8EncodedKeySpec(JavaBase64.getDecoder().decode(privateB64))
        )
        return KeyPair(publicKey, privateKey as PrivateKey)
    }

    private fun softwarePrefs() = SecurePrefs.create(context, PREFS_NAME)

    private fun supportsKeystoreEd25519(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    private fun createEd25519Signature(): java.security.Signature {
        return try {
            java.security.Signature.getInstance("Ed25519")
        } catch (_: Exception) {
            java.security.Signature.getInstance("Ed25519", SecurityUtils.getSecurityProvider())
        }
    }

    private fun prefKey(alias: String, suffix: String): String = "${alias}_$suffix"

    companion object {
        private const val TAG = "AgentKeyManager"
        const val KEY_ALIAS = "stackward-agent-ssh"
        const val KEY_ALIAS_ALT = "stackward-agent-ssh-alt"
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val PREFS_NAME = "stackward_agent_keys"
        private const val SUFFIX_PUBLIC = "public"
        private const val SUFFIX_PRIVATE = "private"
    }
}
