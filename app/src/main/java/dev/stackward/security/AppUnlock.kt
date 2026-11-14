package dev.stackward.security

import dev.stackward.crypto.KeyAccess
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Something that proves the user is present right now (a strong-biometric prompt). */
fun interface UserPresenceGate {
    suspend fun authenticate(title: String, subtitle: String): Boolean
}

/** A password the UI must ask for. [wrong] is true when the previous attempt was rejected. */
class PasswordRequest(
    val mode: Mode,
    val wrong: Boolean,
    internal val result: CompletableDeferred<CharArray?>,
) {
    enum class Mode { UNLOCK, CREATE }

    fun submit(password: CharArray) { result.complete(password) }
    fun cancel() { result.complete(null) }
}

/**
 * Makes sure the SSH key may sign before any host is contacted: one strong-biometric prompt for a Keystore
 * key, or the key password for a password-sealed key. A successful unlock holds for
 * [dev.stackward.crypto.AgentKeyManager.UNLOCK_WINDOW_MS], so "check all hosts" needs ONE confirmation.
 * Concurrent callers are serialized, so two screens never show two prompts at once.
 */
class AppUnlock(
    private val keys: KeyAccess,
    private val activeAlias: () -> String,
) {
    private val mutex = Mutex()
    private val _request = MutableStateFlow<PasswordRequest?>(null)

    /** The password dialog to show right now, or null. Collected by the UI. */
    val request: StateFlow<PasswordRequest?> = _request.asStateFlow()

    suspend fun ensureUnlocked(gate: UserPresenceGate, title: String, subtitle: String): Boolean =
        mutex.withLock {
            val alias = activeAlias()
            if (keys.isUnlocked(alias)) return@withLock true
            if (keys.isPasswordKey(alias)) {
                var wrong = false
                repeat(MAX_PASSWORD_ATTEMPTS) {
                    val password = ask(PasswordRequest.Mode.UNLOCK, wrong) ?: return@withLock false
                    val ok = keys.unlockWithPassword(alias, password)
                    password.fill('\u0000')
                    if (ok) return@withLock true
                    wrong = true
                }
                false
            } else if (gate.authenticate(title, subtitle)) {
                keys.markBiometricAuthorized()
                true
            } else {
                false
            }
        }

    /** Asks for a new key password (twice, in the dialog). Null when cancelled. */
    suspend fun askNewPassword(): CharArray? = mutex.withLock { ask(PasswordRequest.Mode.CREATE, wrong = false) }

    private suspend fun ask(mode: PasswordRequest.Mode, wrong: Boolean): CharArray? {
        val request = PasswordRequest(mode, wrong, CompletableDeferred())
        _request.value = request
        return try {
            request.result.await()
        } finally {
            _request.value = null
        }
    }

    private companion object {
        const val MAX_PASSWORD_ATTEMPTS = 5
    }
}
