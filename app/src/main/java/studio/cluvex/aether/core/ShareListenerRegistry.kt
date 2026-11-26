package studio.cluvex.aether.core

/**
 * A detached group of resources whose close operation is synchronous and idempotent.
 * Concurrent callers serialize on this batch: a later caller cannot return while the
 * first close is still running. The result is false if any close callback fails.
 *
 * The owner detaches resources while holding its state lock, then calls [close] only
 * after releasing that lock. A caller must not rebind a fixed endpoint unless close
 * returns true.
 */
internal class ShareCloseBatch<T>(
    private val entries: List<T>,
    private val closeEntry: (T) -> Unit,
) {
    private var closed = false
    private var closeSucceeded = false

    val size: Int
        get() = entries.size

    @Synchronized
    fun close(): Boolean {
        if (closed) return closeSucceeded
        closed = true
        var succeeded = true
        entries.forEach { entry ->
            if (runCatching { closeEntry(entry) }.isFailure) succeeded = false
        }
        closeSucceeded = succeeded
        return closeSucceeded
    }
}

/**
 * Small owner for a set of listeners. The caller serializes mutations (ShareBridge
 * does so with its object monitor). Detach methods transfer ownership without closing;
 * compatibility retain/closeAll methods preserve the existing synchronous behavior.
 */
internal class ShareListenerRegistry<T>(
    private val close: (T) -> Unit,
) {
    private val entries = mutableListOf<T>()

    fun add(listener: T) {
        entries += listener
    }

    fun any(predicate: (T) -> Boolean): Boolean = entries.any(predicate)

    fun isEmpty(): Boolean = entries.isEmpty()

    fun isNotEmpty(): Boolean = entries.isNotEmpty()

    /** Remove non-matching entries and transfer them to a close batch without closing. */
    fun detachWhere(predicate: (T) -> Boolean): ShareCloseBatch<T> {
        val detached = mutableListOf<T>()
        val iterator = entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (!predicate(entry)) {
                iterator.remove()
                detached += entry
            }
        }
        return ShareCloseBatch(detached, close)
    }

    /** Transfer every currently owned entry to a close batch without closing. */
    fun detachAll(): ShareCloseBatch<T> {
        val detached = entries.toList()
        entries.clear()
        return ShareCloseBatch(detached, close)
    }

    /** Keep matching entries and close every entry removed from the registry. */
    fun retain(predicate: (T) -> Boolean) {
        detachWhere(predicate).close()
    }

    fun <R : Comparable<R>> sortBy(selector: (T) -> R?) {
        entries.sortBy(selector)
    }

    fun snapshot(): List<T> = entries.toList()

    fun closeAll() {
        detachAll().close()
    }
}
