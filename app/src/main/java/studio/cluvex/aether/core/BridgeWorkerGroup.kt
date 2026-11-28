package studio.cluvex.aether.core

/**
 * Owns the reader/writer thread pair for a TUN bridge.
 *
 * It never closes the TUN descriptor: that descriptor belongs to the VPN service.
 * A blocked read may therefore outlive the bounded join and must be released by
 * the descriptor owner during service teardown.
 */
internal class BridgeWorkerGroup(
    private val joinTimeoutMs: Long = 500L,
) {
    private val lock = Any()

    @Volatile
    var isRunning: Boolean = false
        private set

    private var workers: List<Thread> = emptyList()

    fun start(newWorkers: List<Thread>) {
        synchronized(lock) {
            if (isRunning) return
            check(workers.none { it.isAlive }) {
                "Previous TUN workers are still alive; refusing to start another generation"
            }
            workers = newWorkers
            isRunning = true
            try {
                newWorkers.forEach(Thread::start)
            } catch (failure: Throwable) {
                isRunning = false
                newWorkers.forEach(Thread::interrupt)
                joinWorkers(newWorkers)
                workers = newWorkers.filter { it.isAlive }
                throw failure
            }
        }
    }

    /**
     * Requests stop and waits a bounded interval for every non-caller worker.
     * Returns false if a blocking operation (notably TUN read) has not exited.
     */
    fun stop(): Boolean = synchronized(lock) {
        isRunning = false
        val snapshot = workers
        snapshot.forEach(Thread::interrupt)
        joinWorkers(snapshot)
        workers = snapshot.filter { it.isAlive }
        workers.isEmpty()
    }

    private fun joinWorkers(snapshot: List<Thread>) {
        var interrupted = false
        snapshot.forEach { worker ->
            if (worker === Thread.currentThread()) return@forEach
            try {
                worker.join(joinTimeoutMs)
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }
}
