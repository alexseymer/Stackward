package dev.stackward.proxmox

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Socket
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Minimal HTTPS client over an SSH direct tunnel to Proxmox (:8006).
 * TLS trust is relaxed because traffic is confined to the pinned SSH tunnel.
 */
class ProxmoxHttpClient(
    private val tunnel: Socket,
    private val hostHeader: String,
    private val tokenId: String,
    private val tokenSecret: String,
    private val tlsServerName: String,
    private val tlsPort: Int,
) {

    fun request(method: String, apiPath: String, body: String? = null): ProxmoxHttpResponse {
        val normalizedPath = apiPath.removePrefix("/api2/json/").removePrefix("/")
        val requestPath = "/api2/json/$normalizedPath"
        val sslSocket = openTlsSocket()
        sslSocket.use { socket ->
            val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream()))
            val authHeader = "PVEAPIToken=$tokenId=$tokenSecret"
            writer.write("$method $requestPath HTTP/1.1\r\n")
            writer.write("Host: $hostHeader\r\n")
            writer.write("Authorization: $authHeader\r\n")
            writer.write("Accept: application/json\r\n")
            if (body != null) {
                writer.write("Content-Type: application/json\r\n")
                writer.write("Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\n")
            }
            writer.write("Connection: close\r\n")
            writer.write("\r\n")
            if (body != null) {
                writer.write(body)
            }
            writer.flush()

            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val statusLine = reader.readLine()
                ?: throw ProxmoxException("Empty HTTP response from Proxmox API")
            val statusCode = statusLine.substringAfter(" ").substringBefore(" ").toIntOrNull()
                ?: throw ProxmoxException("Invalid HTTP status line: $statusLine")

            var contentLength = -1
            var line: String?
            while (reader.readLine().also { line = it } != null && line!!.isNotEmpty()) {
                val header = line!!
                if (header.startsWith("Content-Length:", ignoreCase = true)) {
                    contentLength = header.substringAfter(":").trim().toIntOrNull() ?: -1
                }
            }

            val responseBody = when {
                contentLength > 0 -> {
                    val buffer = CharArray(contentLength)
                    var read = 0
                    while (read < contentLength) {
                        val chunk = reader.read(buffer, read, contentLength - read)
                        if (chunk < 0) break
                        read += chunk
                    }
                    String(buffer, 0, read)
                }
                else -> reader.readText()
            }

            return ProxmoxHttpResponse(statusCode = statusCode, body = responseBody.trim())
        }
    }

    private fun openTlsSocket(): SSLSocket {
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf<TrustManager>(PermissiveTrustManager()), null)
        val factory = sslContext.socketFactory
        val sslSocket = factory.createSocket(tunnel, tlsServerName, tlsPort, true) as SSLSocket
        sslSocket.soTimeout = 30_000
        sslSocket.startHandshake()
        return sslSocket
    }

    private class PermissiveTrustManager : X509TrustManager {
        override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = emptyArray()
    }
}

data class ProxmoxHttpResponse(
    val statusCode: Int,
    val body: String,
) {
    fun bodyOrThrow(): String {
        if (statusCode !in 200..299) {
            throw ProxmoxException("Proxmox API HTTP $statusCode: $body")
        }
        return body
    }
}

class ProxmoxException(message: String) : Exception(message)
