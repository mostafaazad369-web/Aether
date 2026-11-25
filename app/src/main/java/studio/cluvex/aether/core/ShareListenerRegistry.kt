package studio.cluvex.aether.core

/**
 * Small owner for a set of listeners. The caller serializes mutations (ShareBridge
 * does so with its object monitor); rejected and removed listeners are closed once.
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

    /** Keep matching entries and close every entry removed from the registry. */
    fun retain(predicate: (T) -> Boolean) {
        val iterator = entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (!predicate(entry)) {
                iterator.remove()
                runCatching { close(entry) }
            }
        }
    }

    fun sortBy(selector: (T) -> Comparable<*>?) {
        entries.sortBy(selector)
    }

    fun snapshot(): List<T> = entries.toList()

    fun closeAll() {
        val closing = entries.toList()
        entries.clear()
        closing.forEach { entry -> runCatching { close(entry) } }
    }
}
