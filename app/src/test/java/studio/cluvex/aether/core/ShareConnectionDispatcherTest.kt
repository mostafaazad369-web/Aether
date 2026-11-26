package studio.cluvex.aether.core

import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ShareConnectionDispatcherTest {
    @Test
    fun failedWorkerStartRollsBackAdmissionExactlyOnceAndRethrowsOriginalFailure() {
        val releases = AtomicInteger(0)
        val failure = IllegalStateException("simulated thread start failure")
        var caught: Throwable? = null

        try {
            ShareConnectionDispatcher.dispatch(
                startWorker = { throw failure },
                onFailure = { releases.incrementAndGet() },
            )
        } catch (thrown: Throwable) {
            caught = thrown
        }

        assertSame(failure, caught)
        assertEquals(1, releases.get())
    }

    @Test
    fun cleanupFailureIsSuppressedWithoutReplacingDispatchFailure() {
        val dispatchFailure = IllegalStateException("dispatch")
        val cleanupFailure = IllegalArgumentException("cleanup")
        var caught: Throwable? = null

        try {
            ShareConnectionDispatcher.dispatch(
                startWorker = { throw dispatchFailure },
                onFailure = { throw cleanupFailure },
            )
        } catch (thrown: Throwable) {
            caught = thrown
        }

        assertSame(dispatchFailure, caught)
        assertEquals(listOf(cleanupFailure), dispatchFailure.suppressed.toList())
    }

    @Test
    fun successfulDispatchDoesNotRunRollback() {
        val releases = AtomicInteger(0)
        var started = false

        ShareConnectionDispatcher.dispatch(
            startWorker = { started = true },
            onFailure = { releases.incrementAndGet() },
        )

        assertEquals(true, started)
        assertEquals(0, releases.get())
    }
}
