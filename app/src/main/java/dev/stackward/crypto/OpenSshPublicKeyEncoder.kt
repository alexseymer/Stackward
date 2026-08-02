package dev.stackward.crypto

import android.util.Base64
import java.io.ByteArrayOutputStream
import java.security.PublicKey

/**
 * Encodes Ed25519 public keys in OpenSSH authorized_keys format.
 */
object OpenSshPublicKeyEncoder {

    private const val KEY_TYPE = "ssh-ed25519"
    private const val COMMENT = "stackward-agent"

    fun encode(publicKey: PublicKey, comment: String = COMMENT): String {
        val rawKey = extractEd25519Bytes(publicKey)
        val payload = ByteArrayOutputStream().apply {
            writeMpint(KEY_TYPE.toByteArray(Charsets.UTF_8))
            writeMpint(rawKey)
        }.toByteArray()

        val encoded = Base64.encodeToString(payload, Base64.NO_WRAP)
        return "$KEY_TYPE $encoded $comment"
    }

    /** Unique portion of the public key line used to revoke a specific authorized_keys entry. */
    fun keyMarker(publicKeyLine: String): String {
        val parts = publicKeyLine.trim().split(Regex("\\s+"))
        require(parts.size >= 2) { "Invalid OpenSSH public key line" }
        return parts[1]
    }

    /**
     * Ed25519 public keys in X.509 SPKI encoding end with 32 raw bytes.
     */
    fun extractEd25519Bytes(publicKey: PublicKey): ByteArray {
        val encoded = publicKey.encoded
        require(encoded.size >= 32) {
            "Unexpected Ed25519 public key encoding (${encoded.size} bytes)"
        }
        return encoded.copyOfRange(encoded.size - 32, encoded.size)
    }

    private fun ByteArrayOutputStream.writeMpint(data: ByteArray) {
        val length = data.size
        write((length ushr 24) and 0xFF)
        write((length ushr 16) and 0xFF)
        write((length ushr 8) and 0xFF)
        write(length and 0xFF)
        write(data)
    }
}
