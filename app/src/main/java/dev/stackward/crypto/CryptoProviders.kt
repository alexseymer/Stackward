package dev.stackward.crypto

import android.util.Log
import java.security.Security
import org.bouncycastle.jce.provider.BouncyCastleProvider

/**
 * Android registers a stripped-down BouncyCastle as "BC" that lacks modern
 * algorithms (X25519, full Ed25519). SSHJ expects a complete BC provider.
 */
object CryptoProviders {

    private const val TAG = "CryptoProviders"

    fun install() {
        val existing = Security.getProvider(BouncyCastleProvider.PROVIDER_NAME)
        if (existing != null && existing.javaClass == BouncyCastleProvider::class.java) {
            return
        }
        if (existing != null) {
            Log.i(TAG, "Replacing Android BC provider (${existing.javaClass.name})")
            Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
        }
        Security.insertProviderAt(BouncyCastleProvider(), 1)
        Log.i(TAG, "Installed full BouncyCastle provider")
    }
}
