package studio.cluvex.aether.core

import java.util.concurrent.atomic.AtomicBoolean

/**
 * A detached group of resources whose close operation is synchronous and idempotent.
 *
 * The owner detaches the resources while holding its state lock, then calls [close]
 * only after releasing that lock. A caller that must rebind a fixed endpoint must
 * wait for this method to return before starting the replacement bind.
 */
internal class ShareCloseBatch<T>(
    private val entries: List<T>,
    private val closeEntry: (T) -> Unit,
) {
    private val closed = AtomicBoolean(false)

    val size: Int
        get() = entries.size

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        entries.forEach { entry -> runCatching { closeEntry(entry) } }
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
