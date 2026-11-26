package studio.cluvex.aether.core

/**
 * Serializes the final worker handoff with teardown using the owner's monitor.
 */
internal object ShareConnectionHandoff {
    fun startIfCurrent(
        lock: Any,
        isCurrent: () -> Boolean,
        startWorker: () -> Unit,
    ) {
        synchronized(lock) {
            check(isCurrent()) { "Connection listener generation stopped before dispatch." }
            startWorker()
        }
    }
}
