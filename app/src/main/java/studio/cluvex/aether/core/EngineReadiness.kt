package studio.cluvex.aether.core

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Waits for the native engine to serve SOCKS5, not merely bind its TCP port. */
object EngineReadiness {
    enum class Outcome { READY, DIED, TIMEOUT }

    private const val POLL_MS = 150L
    private const val BLIND_GREETING_EVERY_MS = 2_000L

    suspend fun await(
        engine: AetherProcess,
        host: String,
        port: Int,
        budgetMs: Long,
        label: String = "engine",
        abort: () -> Boolean = { false },
    ): Outcome = withContext(Dispatchers.IO) {
        val started = SystemClock.elapsedRealtime()
        val deadline = started + budgetMs.coerceAtLeast(1_000L)
        var lastBlind = 0L
        var lastServingTry = 0L
        while (SystemClock.elapsedRealtime() < deadline) {
            if (!engine.isAlive()) {
                engine.fatalLine?.let { DiagnosticsLog.w("ready", "$label stopped: $it") }
                return@withContext Outcome.DIED
            }
            if (abort()) return@withContext Outcome.TIMEOUT
            val now = SystemClock.elapsedRealtime()
            if (engine.isServing) {
                if (now - lastServingTry >= 300L) {
                    lastServingTry = now
                    if (NetProbe.checkSocksHandshake(host, port, 2_000)) {
                        DiagnosticsLog.i("ready", "$label is serving SOCKS5 on $host:$port after ${now - started} ms.")
                        return@withContext Outcome.READY
                    }
                }
            } else if (now - lastBlind >= BLIND_GREETING_EVERY_MS) {
                lastBlind = now
                if (NetProbe.checkSocksHandshake(host, port, 1_000)) {
                    DiagnosticsLog.i("ready", "$label answered SOCKS5 on $host:$port after ${SystemClock.elapsedRealtime() - started} ms.")
                    return@withContext Outcome.READY
                }
            }
            delay(POLL_MS)
        }
        DiagnosticsLog.w("ready", "$label did not start serving SOCKS5 within ${budgetMs / 1000}s.")
        Outcome.TIMEOUT
    }
}
