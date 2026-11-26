package studio.cluvex.aether.core

import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

class HevTunnelLifecycleTest {
    @Test
    fun returnsImmediatelyWhenNoNativeWorkerIsActive() = runBlocking {
        assertTrue(awaitNativeWorkerStopped(timeoutMs = 100) { false })
    }

    @Test
    fun waitsUntilTheNativeWorkerPublishesExit() = runBlocking {
        val active = AtomicBoolean(true)
        val worker = Thread {
            Thread.sleep(60)
            active.set(false)
        }.apply { start() }

        try {
            assertTrue(awaitNativeWorkerStopped(timeoutMs = 1_000) { active.get() })
        } finally {
            worker.join(1_000)
        }
        assertFalse(worker.isAlive)
    }

    @Test
    fun timesOutWhileNativeWorkerRemainsActive() = runBlocking {
        assertFalse(awaitNativeWorkerStopped(timeoutMs = 60) { true })
    }


    @Test
    fun startWaitsForWorkerExitBeforeInvokingStart() = runBlocking {
        val active = AtomicBoolean(true)
        val started = AtomicBoolean(false)
        val worker = Thread {
            Thread.sleep(60)
            active.set(false)
        }.apply { start() }

        try {
            assertTrue(
                startAfterNativeWorkerStopped(
                    timeoutMs = 1_000,
                    lifecycleLock = Any(),
                    isActive = { active.get() },
                    start = { started.set(true) },
                ),
            )
        } finally {
            worker.join(1_000)
        }
        assertFalse(worker.isAlive)
        assertTrue(started.get())
    }

    @Test
    fun startRechecksWorkerStateAfterAcquiringLifecycleLock() {
        val active = AtomicBoolean(false)
        val observedIdle = CountDownLatch(1)
        val releaseLock = CountDownLatch(1)
        val startCalled = CountDownLatch(1)
        val lifecycleLock = Any()
        val lockHolder = Thread {
            synchronized(lifecycleLock) {
                observedIdle.countDown()
                releaseLock.await(1, TimeUnit.SECONDS)
            }
        }
        lockHolder.start()
        assertTrue(observedIdle.await(1, TimeUnit.SECONDS))

        val result = java.util.concurrent.atomic.AtomicReference<Boolean?>(null)
        val starter = Thread {
            result.set(
                runBlocking {
                    startAfterNativeWorkerStopped(
                        timeoutMs = 1_000,
                        lifecycleLock = lifecycleLock,
                        isActive = { active.get() },
                        start = { startCalled.countDown() },
                    )
                },
            )
        }
        starter.start()
        // The poller observes idle, then blocks behind lockHolder.
        Thread.sleep(40)
        active.set(true)
        releaseLock.countDown()
        // The in-lock recheck must reject this start while the old worker drains.
        Thread.sleep(40)
        assertEquals(1L, startCalled.count)
        active.set(false)
        assertTrue(startCalled.await(1, TimeUnit.SECONDS))
        starter.join(1_000)
        lockHolder.join(1_000)
        assertFalse(starter.isAlive)
        assertFalse(lockHolder.isAlive)
        assertEquals(true, result.get())
    }

    @Test
    fun startTimesOutWithoutInvokingStartWhenWorkerNeverExits() = runBlocking {
        val started = AtomicBoolean(false)
        assertFalse(
            startAfterNativeWorkerStopped(
                timeoutMs = 60,
                lifecycleLock = Any(),
                isActive = { true },
                start = { started.set(true) },
            ),
        )
        assertFalse(started.get())
    }

}
