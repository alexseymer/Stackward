package dev.stackward.crypto

import java.security.KeyPair
import java.security.PublicKey

/**
 * Manages the agent's ed25519 SSH keypair in Android Keystore.
 *
 * Phase 1 responsibilities:
 * - Generate ed25519 keypair with [setUserAuthenticationRequired(true)]
 * - Non-exportable, ideally StrongBox-backed
 * - Every signing operation requires a fresh biometric prompt
 * - Export public key in OpenSSH format for authorized_keys
 * - Sign SSH auth challenges
 *
 * The private key never leaves Keystore. Biometric data never leaves the device.
 */
class AgentKeyManager {

    /**
     * Generate a new ed25519 keypair in Android Keystore.
     * Requires biometric authentication for every use after creation.
     */
    fun generateKeypair(alias: String = KEY_ALIAS): KeyPair {
        // TODO: Use KeyGenParameterSpec.Builder with:
        //   - KeyProperties.KEY_ALGORITHM_EC (or ed25519 when available)
        //   - setUserAuthenticationRequired(true)
        //   - setInvalidatedByBiometricEnrollment(true)
        //   - setIsStrongBoxBacked(true) when hardware available
        TODO("Phase 1: implement Keystore key generation")
    }

    /**
     * Return the public key in OpenSSH authorized_keys format.
     */
    fun getPublicKeyOpenSSH(alias: String = KEY_ALIAS): String {
        TODO("Phase 1: export public key as ssh-ed25519 AAAA... comment")
    }

    /**
     * Sign data after biometric authentication.
     * Called by the SSH layer for auth challenges.
     */
    fun sign(data: ByteArray, alias: String = KEY_ALIAS): ByteArray {
        TODO("Phase 1: sign with Keystore key after biometric prompt")
    }

    /**
     * Check whether a keypair already exists for this alias.
     */
    fun hasKeypair(alias: String = KEY_ALIAS): Boolean {
        TODO("Phase 1: check Keystore for existing key")
    }

    /**
     * Delete the keypair (for rotation or panic revoke).
     */
    fun deleteKeypair(alias: String = KEY_ALIAS) {
        TODO("Phase 1: remove key from Keystore")
    }

  companion object {
        const val KEY_ALIAS = "stackward-agent-ssh"
    }
}

/**
 * Stores a Proxmox API token alongside the SSH key, same biometric gate.
 */
class ProxmoxTokenStore {

    fun storeToken(tokenId: String, tokenSecret: String) {
        // TODO: store in EncryptedSharedPreferences or Keystore-wrapped secret
        TODO("Phase 1: store Proxmox API token securely")
    }

    fun getToken(): Pair<String, String>? {
        // TODO: retrieve token id + secret after biometric auth
        TODO("Phase 1: retrieve Proxmox API token")
    }

    fun deleteToken() {
        TODO("Phase 1: delete stored Proxmox token")
    }
}
