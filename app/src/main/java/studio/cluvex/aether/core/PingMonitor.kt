package studio.cluvex.aether.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import studio.cluvex.aether.custom.RealDelay

data class PingResult(val ms: Long = -1L, val running: Boolean = false, val error: Boolean = false)

/** Home and catalog use the same certificate-validated HTTPS generate_204 metric.
 * Home retains its channel to avoid repeatedly opening SSH/Tor channels. No historic
 * floor is substituted for an actual sample. A chained pipeline may still differ
 * from the catalog's direct config path; equal numbers are not guaranteed.
 */
object PingMonitor {
    private val _state = MutableStateFlow(PingResult())
    val state: StateFlow<PingResult> = _state.asStateFlow()
    // Bounded real probe history for latency/loss details; -1 records a lost probe.
    private val _history = MutableStateFlow<List<Long>>(emptyList())
    val history: StateFlow<List<Long>> = _history.asStateFlow()
    private val mutex = Mutex()
    private var tunnelPort = PortLease.socks
    private var epoch = 0L
    private var session: RealDelay.ProbeSession? = null
    private var sessionViaTunnel = true

    @Synchronized fun setTunnelPort(port: Int) {
        if (port in 1..65535 && port != tunnelPort) { tunnelPort = port; reset() }
    }
    @Synchronized fun resetTunnelPort() { setTunnelPort(PortLease.socks) }
    fun probeTargets(): List<Pair<String, Int>> = RealDelay.TARGETS.map { it to 443 }
    /** Compatibility: setup is deliberately not reported as a separate measured RTT. */
    fun lastSetupMs(): Long = -1L

    @Synchronized fun reset() {
        epoch++
        session?.close(); session = null
        _state.value = PingResult()
        _history.value = emptyList()
    }

    suspend fun pingOnce(viaTunnel: Boolean) {
        if (!mutex.tryLock()) return
        try {
            val snapshot = synchronized(this) {
                if (session != null && sessionViaTunnel != viaTunnel) reset()
                sessionViaTunnel = viaTunnel
                val current = session ?: RealDelay.ProbeSession(if (viaTunnel) tunnelPort else 0,
                    RealDelay.TARGETS.first(), RealDelay.REQUEST_TIMEOUT_MS, "", "", true, false).also { session = it }
                _state.value = PingResult(running = true)
                epoch to current
            }
            val result = withContext(Dispatchers.IO) { RealDelay.measureSession(snapshot.second) }
            synchronized(this) {
                // A result from a torn-down/changed tunnel must never overwrite the new state.
                if (snapshot.first != epoch) {
                    // reset() may race the first attempt; also close a channel reopened
                    // by that attempt's retry, never retain it after the tunnel is gone.
                    snapshot.second.close()
                } else {
                    val measured = when (result) {
                        is RealDelay.Result.Ok -> result.ms
                        else -> -1L
                    }
                    _state.value = if (measured >= 0L) PingResult(ms = measured) else PingResult(error = true)
                    _history.value = (_history.value + measured).takeLast(MAX_HISTORY)
                }
            }
        } finally { mutex.unlock() }
    }

    private const val MAX_HISTORY = 60
}
