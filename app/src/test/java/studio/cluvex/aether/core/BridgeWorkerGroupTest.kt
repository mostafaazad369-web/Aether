package studio.cluvex.aether.core

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeWorkerGroupTest {
    @Test
    fun stopInterruptsAndJoinsWorkers() {
        val group = BridgeWorkerGroup(joinTimeoutMs = 500)
        val started = CountDownLatch(2)
        val workers = List(2) {
            Thread {
                started.countDown()
                try {
                    Thread.sleep(30_000)
                } catch (_: InterruptedException) {
                    // Expected stop signal.
                }
            }
        }

        group.start(workers)
        assertTrue(started.await(1, TimeUnit.SECONDS))
        assertTrue(group.isRunning)
        assertTrue(group.stop())
        assertFalse(group.isRunning)
        assertTrue(workers.none { it.isAlive })
    }

    @Test
    fun startFailureRollsBackAlreadyStartedWorkers() {
        val group = BridgeWorkerGroup(joinTimeoutMs = 500)
        val started = CountDownLatch(1)
        val first = Thread {
            started.countDown()
            try {
                Thread.sleep(30_000)
            } catch (_: InterruptedException) {
                // Expected rollback signal.
            }
        }
        val failing = object : Thread() {
            override fun start() {
                throw IllegalStateException("injected start failure")
            }
        }

        assertThrows(IllegalStateException::class.java) {
            group.start(listOf(first, failing))
        }
        assertTrue(started.await(1, TimeUnit.SECONDS))
        assertFalse(group.isRunning)
        assertFalse(first.isAlive)
    }

    @Test
    fun workerCanRequestItsOwnStopWithoutJoiningItself() {
        val group = BridgeWorkerGroup(joinTimeoutMs = 100)
        val stopReturned = CountDownLatch(1)
        val stopResult = java.util.concurrent.atomic.AtomicBoolean(true)
        val worker = Thread {
            stopResult.set(group.stop())
            stopReturned.countDown()
        }

        group.start(listOf(worker))

        assertTrue(stopReturned.await(1, TimeUnit.SECONDS))
        assertFalse("the current worker is still alive while stop() returns", stopResult.get())
        worker.join(1_000)
        assertFalse(worker.isAlive)

        val replacement = Thread {}
        group.start(listOf(replacement))
        replacement.join(1_000)
        assertFalse(replacement.isAlive)
    }

    @Test
    fun refusesRestartWhileAnOldWorkerIsStillBlocked() {
        val group = BridgeWorkerGroup(joinTimeoutMs = 10)
        val release = CountDownLatch(1)
        val blocked = Thread {
            while (true) {
                try {
                    release.await()
                    break
                } catch (_: InterruptedException) {
                    // Simulate a blocking operation that ignores interrupt.
                }
            }
        }

        group.start(listOf(blocked))
        assertFalse(group.stop())
        assertThrows(IllegalStateException::class.java) {
            group.start(listOf(Thread {}))
        }
        release.countDown()
        blocked.join(1_000)
        assertTrue(group.start(listOf(Thread {})).let { true })
        assertTrue(group.stop())
    }
}
