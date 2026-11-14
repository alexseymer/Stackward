package dev.stackward.crypto

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import net.schmizz.sshj.common.SecurityUtils
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64 as JavaBase64
import java.util.concurrent.ConcurrentHashMap

/** What the unlock logic needs from the key store (an interface so it is unit-testable). */
interface KeyAccess {
    fun isUnlocked(alias: String): Boolean
    fun isPasswordKey(alias: String): Boolean
    /** True when the password was right; the key then stays usable for [AgentKeyManager.UNLOCK_WINDOW_MS]. */
    fun unlockWithPassword(alias: String, password: CharArray): Boolean
    /** Call after a successful strong-biometric prompt: Keystore keys sign for [AgentKeyManager.UNLOCK_WINDOW_MS]. */
    fun markBiometricAuthorized()
}

/**
 * Manages the agent's Ed25519 SSH keypair.
 *
 * - API 33+ with a strong biometric enrolled: hardware-backed Keystore key that signs for a short window
 *   after a biometric prompt (one fingerprint covers a whole "check all").
 * - Otherwise: a software key sealed under a user password (PBKDF2 + AES-GCM); the decrypted key lives in
 *   memory only for the same window. There is no silent unprotected fallback.
 */
class AgentKeyManager(
    private val context: Context,
) : KeyAccess {

    private val keyStore: KeyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }

    private val unlockedKeys = ConcurrentHashMap<String, Pair<KeyPair, Long>>()
    @Volatile private var biometricAuthorizedUntil = 0L

    /** True when a new key must be sealed under a password (no biometric-bound Keystore key possible). */
    fun needsPasswordForNewKey(): Boolean = !canUseKeystoreEd25519()

    fun generateKeypair(
        alias: String = KEY_ALIAS,
        replaceExisting: Boolean = true,
        password: CharArray? = null,
    ): KeyPair {
        if (replaceExisting) {
            deleteKeypair(alias)
        } else if (hasKeypair(alias)) {
            throw IllegalStateException("Keypair already exists for alias: $alias")
        }

        if (canUseKeystoreEd25519()) {
            try {
                return generateKeystoreKeypair(alias)
            } catch (error: Exception) {
                android.util.Log.w(TAG, "Keystore Ed25519 failed", error)
                deleteKeypair(alias)
            }
        }
        val pw = password ?: throw IllegalArgumentException("A password is required for a software key")
        require(PasswordKeyCrypto.isAcceptable(pw)) {
            "Password must be at least ${PasswordKeyCrypto.MIN_PASSWORD_LENGTH} characters"
        }
        return generatePasswordKeypair(alias, pw)
    }

    fun getPublicKeyOpenSSH(alias: String = KEY_ALIAS): String {
        val publicKey = getPublicKey(alias)
            ?: throw IllegalStateException("No keypair found for alias: $alias")
        return OpenSshPublicKeyEncoder.encode(publicKey)
    }

    /** The signing key pair. Throws [KeyLockedException] when the key needs a fresh unlock. */
    fun getKeyPair(alias: String = KEY_ALIAS): KeyPair? {
        if (!hasKeypair(alias)) return null
        if (isKeystoreKey(alias)) {
            if (!isUnlocked(alias)) throw KeyLockedException()
            val entry = keyStore.getEntry(alias, null) as KeyStore.PrivateKeyEntry
            return KeyPair(entry.certificate.publicKey, entry.privateKey)
        }
        return unlockedKeyPair(alias) ?: throw KeyLockedException()
    }

    fun hasKeypair(alias: String = KEY_ALIAS): Boolean =
        isKeystoreKey(alias) || softwarePrefs().contains(prefKey(alias, SUFFIX_SEALED))

    fun deleteKeypair(alias: String = KEY_ALIAS) {
        if (keyStore.containsAlias(alias)) {
            keyStore.deleteEntry(alias)
        }
        unlockedKeys.remove(alias)
        softwarePrefs().edit()
            .remove(prefKey(alias, SUFFIX_PUBLIC))
            .remove(prefKey(alias, SUFFIX_PRIVATE)) // legacy unencrypted private key from older builds
            .remove(prefKey(alias, SUFFIX_SEALED))
            .remove(prefKey(alias, SUFFIX_SALT))
            .remove(prefKey(alias, SUFFIX_IV))
            .remove(prefKey(alias, SUFFIX_ITERATIONS))
            .apply()
    }

    fun usesHardwareKeystore(): Boolean = isKeystoreKey(KEY_ALIAS)

    // --- unlock state -------------------------------------------------------------------------

    override fun isPasswordKey(alias: String): Boolean =
        !isKeystoreKey(alias) && softwarePrefs().contains(prefKey(alias, SUFFIX_SEALED))

    override fun isUnlocked(alias: String): Boolean = when {
        isKeystoreKey(alias) -> System.currentTimeMillis() < biometricAuthorizedUntil
        else -> unlockedKeyPair(alias) != null
    }

    override fun markBiometricAuthorized() {
        biometricAuthorizedUntil = System.currentTimeMillis() + UNLOCK_WINDOW_MS
    }

    override fun unlockWithPassword(alias: String, password: CharArray): Boolean {
        val prefs = softwarePrefs()
        val sealed = readSealed(alias, prefs) ?: return false
        val plain = PasswordKeyCrypto.open(sealed, password) ?: return false
        return try {
            val keyFactory = SecurityUtils.getKeyFactory("Ed25519")
            val publicKey = readPublic(alias, prefs)
            val privateKey = keyFactory.generatePrivate(PKCS8EncodedKeySpec(plain)) as PrivateKey
            unlockedKeys[alias] = KeyPair(publicKey, privateKey) to (System.currentTimeMillis() + UNLOCK_WINDOW_MS)
            true
        } finally {
            plain.fill(0)
        }
    }

    /** Forgets every in-memory key and authorization (e.g. on wipe). */
    fun lock() {
        unlockedKeys.clear()
        biometricAuthorizedUntil = 0L
    }

    private fun unlockedKeyPair(alias: String): KeyPair? {
        val (pair, until) = unlockedKeys[alias] ?: return null
        if (System.currentTimeMillis() >= until) {
            unlockedKeys.remove(alias)
            return null
        }
        return pair
    }

    // --- key creation -------------------------------------------------------------------------

    private fun getPublicKey(alias: String): java.security.PublicKey? = when {
        isKeystoreKey(alias) -> keyStore.getCertificate(alias)?.publicKey
        softwarePrefs().contains(prefKey(alias, SUFFIX_PUBLIC)) -> readPublic(alias, softwarePrefs())
        else -> null
    }

    private fun generateKeystoreKeypair(alias: String): KeyPair {
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
        ).apply {
            // Android Keystore Ed25519 uses EC + ECGenParameterSpec("ed25519").
            setAlgorithmParameterSpec(ECGenParameterSpec("ed25519"))
            setDigests(KeyProperties.DIGEST_NONE)
            setUserAuthenticationRequired(true)
            setInvalidatedByBiometricEnrollment(true)
            // A strong-biometric prompt authorizes signing for a short window, so one fingerprint
            // covers "check all hosts" (PRD Q1). A per-use key would need a CryptoObject per signature.
            setUserAuthenticationParameters((UNLOCK_WINDOW_MS / 1000).toInt(), KeyProperties.AUTH_BIOMETRIC_STRONG)
            setUnlockedDeviceRequired(true)
        }.build()

        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE_PROVIDER)
        generator.initialize(spec)
        return generator.generateKeyPair()
    }

    private fun generatePasswordKeypair(alias: String, password: CharArray): KeyPair {
        val keyPair = try {
            KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        } catch (_: Exception) {
            SecurityUtils.getKeyPairGenerator("Ed25519").generateKeyPair()
        }
        val privateBytes = keyPair.private.encoded
        val sealed = try {
            PasswordKeyCrypto.seal(privateBytes, password)
        } finally {
            privateBytes.fill(0)
        }
        softwarePrefs().edit()
            .putString(prefKey(alias, SUFFIX_PUBLIC), JavaBase64.getEncoder().encodeToString(keyPair.public.encoded))
            .putString(prefKey(alias, SUFFIX_SEALED), JavaBase64.getEncoder().encodeToString(sealed.ciphertext))
            .putString(prefKey(alias, SUFFIX_SALT), JavaBase64.getEncoder().encodeToString(sealed.salt))
            .putString(prefKey(alias, SUFFIX_IV), JavaBase64.getEncoder().encodeToString(sealed.iv))
            .putInt(prefKey(alias, SUFFIX_ITERATIONS), sealed.iterations)
            .remove(prefKey(alias, SUFFIX_PRIVATE))
            .apply()
        // The user just typed the password, so the key is usable right away for the same window.
        unlockedKeys[alias] = keyPair to (System.currentTimeMillis() + UNLOCK_WINDOW_MS)
        return keyPair
    }

    private fun readSealed(alias: String, prefs: android.content.SharedPreferences): PasswordKeyCrypto.Sealed? {
        val decoder = JavaBase64.getDecoder()
        val ct = prefs.getString(prefKey(alias, SUFFIX_SEALED), null) ?: return null
        val salt = prefs.getString(prefKey(alias, SUFFIX_SALT), null) ?: return null
        val iv = prefs.getString(prefKey(alias, SUFFIX_IV), null) ?: return null
        return PasswordKeyCrypto.Sealed(
            salt = decoder.decode(salt),
            iv = decoder.decode(iv),
            ciphertext = decoder.decode(ct),
            iterations = prefs.getInt(prefKey(alias, SUFFIX_ITERATIONS), PasswordKeyCrypto.ITERATIONS),
        )
    }

    private fun readPublic(alias: String, prefs: android.content.SharedPreferences): java.security.PublicKey {
        val b64 = prefs.getString(prefKey(alias, SUFFIX_PUBLIC), null)
            ?: throw IllegalStateException("No public key stored for $alias")
        return SecurityUtils.getKeyFactory("Ed25519")
            .generatePublic(X509EncodedKeySpec(JavaBase64.getDecoder().decode(b64)))
    }

    private fun isKeystoreKey(alias: String): Boolean =
        supportsKeystoreEd25519() && keyStore.containsAlias(alias)

    private fun softwarePrefs() = SecurePrefs.create(context, PREFS_NAME)

    private fun supportsKeystoreEd25519(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /** A Keystore key needs API 33+ and an enrolled strong biometric to authorize it. */
    private fun canUseKeystoreEd25519(): Boolean {
        if (!supportsKeystoreEd25519()) return false
        val canAuth = BiometricManager.from(context)
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        return canAuth == BiometricManager.BIOMETRIC_SUCCESS
    }

    private fun prefKey(alias: String, suffix: String): String = "${alias}_$suffix"

    companion object {
        private const val TAG = "AgentKeyManager"
        const val KEY_ALIAS = "stackward-agent-ssh"
        const val KEY_ALIAS_ALT = "stackward-agent-ssh-alt"

        /** How long one fingerprint or password keeps the key usable (covers "check all hosts"). */
        const val UNLOCK_WINDOW_MS = 60_000L

        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val PREFS_NAME = "stackward_agent_keys"
        private const val SUFFIX_PUBLIC = "public"
        private const val SUFFIX_PRIVATE = "private"
        private const val SUFFIX_SEALED = "sealed"
        private const val SUFFIX_SALT = "salt"
        private const val SUFFIX_IV = "iv"
        private const val SUFFIX_ITERATIONS = "iterations"
    }
}
