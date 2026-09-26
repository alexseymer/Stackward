package dev.stackward.onboarding

import android.util.Log
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Optional TCP port-knock sequence before SSH (memory-only config, never persisted).
 */
object PortKnocker {

    private const val TAG = "Stackward"

    suspend fun knock(
        host: String,
        ports: List<Int>,
        delayMs: Long = 200L,
        connectTimeoutMs: Int = 400,
    ) = withContext(Dispatchers.IO) {
        Log.i(TAG, "PortKnock: $host ports=$ports")
        for (port in ports) {
            require(port in 1..65535) { "Invalid knock port: $port" }
            val ok = runCatching {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
                }
            }.isSuccess
            Log.d(TAG, "PortKnock: $host:$port connected=$ok")
            delay(delayMs)
        }
    }

    fun parseSequence(raw: String): List<Int> {
        if (raw.isBlank()) return emptyList()
        return raw.split(',', ' ', ';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { it.toInt() }
    }
}
