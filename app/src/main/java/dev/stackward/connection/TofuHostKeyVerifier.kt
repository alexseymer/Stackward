package dev.stackward.connection

import android.util.Base64
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.security.MessageDigest
import java.security.PublicKey

/**
 * Trust-on-first-use host key verification with persistent pinning.
 */
class TofuHostKeyVerifier(
    private val pinStore: HostKeyPinStore,
    private val host: String,
    private val port: Int,
) : HostKeyVerifier {

    var lastFingerprint: String? = null
        private set

    override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
        val fingerprint = HostKeyFingerprint.sha256Base64(key)
        lastFingerprint = fingerprint

        val pinned = pinStore.getPin(host, this.port)
        return when {
            pinned == null -> {
                pinStore.savePin(host, this.port, fingerprint)
                true
            }
            pinned == fingerprint -> true
            else -> throw SshException(
                "Host key changed for $host:${this.port}. " +
                    "Pinned=$pinned, actual=$fingerprint. Possible MITM.",
            )
        }
    }

    override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()
}

object HostKeyFingerprint {
    fun sha256Base64(publicKey: PublicKey): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(publicKey.encoded)
        return Base64.encodeToString(digest, Base64.NO_WRAP)
    }

    fun openSshLabel(fingerprint: String): String = "SHA256:$fingerprint"
}
