package studio.cluvex.aether.core

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
    fun workerFinallyAndDispatchRollbackReleaseAdmissionOnlyOnce() {
        val releases = AtomicInteger(0)
        val admission = ShareConnectionRelease { releases.incrementAndGet() }
        val ready = CountDownLatch(2)
        val go = CountDownLatch(1)
        val workerFinally = Thread {
            ready.countDown()
            go.await()
            admission.release()
        }
        val dispatchRollback = Thread {
            ready.countDown()
            go.await()
            admission.release()
        }

        workerFinally.start()
        dispatchRollback.start()
        ready.await()
        go.countDown()
        workerFinally.join(1_000)
        dispatchRollback.join(1_000)

        assertEquals(false, workerFinally.isAlive)
        assertEquals(false, dispatchRollback.isAlive)
        assertEquals(1, releases.get())
    }

    @Test
    fun stopBeforeDispatchRejectsStaleWorkerHandoff() {
        val lock = Any()
        var current = true
        var started = false

        synchronized(lock) { current = false }
        try {
            ShareConnectionHandoff.startIfCurrent(
                lock = lock,
                isCurrent = { current },
                startWorker = { started = true },
            )
        } catch (_: IllegalStateException) {
            // Expected: teardown invalidated the generation first.
        }

        assertEquals(false, started)
    }

    @Test
    fun dispatchBeforeStopCompletesHandoffBeforeTeardownCanInvalidate() {
        val lock = Any()
        var current = true
        var started = false
        val handoffEntered = CountDownLatch(1)
        val allowHandoffToFinish = CountDownLatch(1)
        val teardownFinished = CountDownLatch(1)

        val dispatch = Thread {
            ShareConnectionHandoff.startIfCurrent(
                lock = lock,
                isCurrent = { current },
                startWorker = {
                    started = true
                    handoffEntered.countDown()
                    check(allowHandoffToFinish.await(1, TimeUnit.SECONDS))
                },
            )
        }
        dispatch.start()
        assertEquals(true, handoffEntered.await(1, TimeUnit.SECONDS))

        val teardown = Thread {
            synchronized(lock) { current = false }
            teardownFinished.countDown()
        }
        teardown.start()

        allowHandoffToFinish.countDown()
        dispatch.join(1_000)
        teardown.join(1_000)

        assertEquals(false, dispatch.isAlive)
        assertEquals(false, teardown.isAlive)
        assertEquals(true, teardownFinished.count == 0L)
        assertEquals(true, started)
        assertEquals(false, current)
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
