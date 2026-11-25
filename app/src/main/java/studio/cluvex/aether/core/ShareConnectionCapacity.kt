package studio.cluvex.aether.core

import java.util.concurrent.atomic.AtomicInteger

/**
 * Atomic admission gate for a bounded number of active shared-proxy handlers.
 * A read-then-increment check is not sufficient when accept loops run concurrently.
 */
internal object ShareConnectionCapacity {
    fun tryAcquire(counter: AtomicInteger, limit: Int): Boolean {
        require(limit > 0) { "limit must be positive" }
        while (true) {
            val current = counter.get()
            if (current >= limit) return false
            if (counter.compareAndSet(current, current + 1)) return true
        }
    }
}
