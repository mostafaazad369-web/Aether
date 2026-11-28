package studio.cluvex.aether.core

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Exactly-once release for admission resources shared by worker-finally and
 * dispatch-failure rollback paths.
 */
internal class ShareConnectionRelease(
    private val releaseResources: () -> Unit,
) {
    private val released = AtomicBoolean(false)

    fun release() {
        if (released.compareAndSet(false, true)) releaseResources()
    }
}
