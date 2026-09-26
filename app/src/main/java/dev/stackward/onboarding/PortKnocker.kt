package dev.stackward.onboarding

import java.net.InetSocketAddress
import java.net.Socket

/**
 * Optional TCP port knock before SSH connect (comma/space/semicolon separated ports).
 */
object PortKnocker {

    private const val CONNECT_TIMEOUT_MS = 500
    private const val INTER_KNOCK_DELAY_MS = 200L

    fun parseSequence(raw: String): List<Int> {
        if (raw.isBlank()) return emptyList()
        return raw.split(',', ';', ' ')
            .mapNotNull { token -> token.trim().toIntOrNull()?.takeIf { port -> port in 1..65535 } }
    }

    fun knock(host: String, ports: List<Int>) {
        if (ports.isEmpty()) return
        for (port in ports) {
            runCatching {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                }
            }
            if (port != ports.last()) {
                Thread.sleep(INTER_KNOCK_DELAY_MS)
            }
        }
    }
}
