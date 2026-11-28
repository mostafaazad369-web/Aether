package studio.cluvex.aether.core

/**
 * Keeps admission rollback deterministic when a connection worker cannot start.
 * The callback owns all resources and counters reserved before dispatch.
 */
internal object ShareConnectionDispatcher {
    fun dispatch(startWorker: () -> Unit, onFailure: () -> Unit) {
        try {
            startWorker()
        } catch (failure: Throwable) {
            try {
                onFailure()
            } catch (cleanupFailure: Throwable) {
                failure.addSuppressed(cleanupFailure)
            }
            throw failure
        }
    }
}
