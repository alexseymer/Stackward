package dev.stackward.proxmox

import net.schmizz.sshj.connection.channel.direct.DirectConnection
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket

/**
 * Adapts SSHJ [DirectConnection] to [Socket] so [SSLSocketFactory] can wrap the tunnel.
 */
internal class DirectConnectionSocket(
    private val connection: DirectConnection,
) : Socket() {

    override fun getInputStream(): InputStream = connection.inputStream

    override fun getOutputStream(): OutputStream = connection.outputStream

    override fun close() {
        connection.close()
    }

    override fun isConnected(): Boolean = true

    override fun isClosed(): Boolean = false

    override fun shutdownInput() = Unit

    override fun shutdownOutput() = Unit
}
