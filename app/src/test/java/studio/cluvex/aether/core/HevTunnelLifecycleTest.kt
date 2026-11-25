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
}
