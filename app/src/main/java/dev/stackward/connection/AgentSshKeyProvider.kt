package dev.stackward.connection

import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import java.security.PrivateKey
import java.security.PublicKey

/**
 * SSHJ [KeyProvider] backed by an in-memory Ed25519 [java.security.KeyPair].
 */
class AgentSshKeyProvider(
    private val keyPair: java.security.KeyPair,
) : KeyProvider {

    override fun getPrivate(): PrivateKey = keyPair.private

    override fun getPublic(): PublicKey = keyPair.public

    override fun getType(): KeyType = KeyType.ED25519
}
