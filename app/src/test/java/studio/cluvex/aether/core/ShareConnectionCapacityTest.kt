package studio.cluvex.aether.core

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareConnectionCapacityTest {
    @Test
    fun concurrentAdmissionsNeverExceedTheConfiguredLimit() {
        val limit = 8
        val attempts = 64
        val counter = AtomicInteger(0)
        val ready = CountDownLatch(attempts)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(attempts)

        try {
            val results = (1..attempts).map {
                executor.submit<Boolean> {
                    ready.countDown()
                    check(start.await(5, TimeUnit.SECONDS))
                    ShareConnectionCapacity.tryAcquire(counter, limit)
                }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()

            val acquired = results.count { it.get(5, TimeUnit.SECONDS) }
            assertEquals(limit, acquired)
            assertEquals(limit, counter.get())
            assertFalse(ShareConnectionCapacity.tryAcquire(counter, limit))
        } finally {
            start.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun availableCapacityCanBeAcquiredUntilLimit() {
        val counter = AtomicInteger(0)

        assertTrue(ShareConnectionCapacity.tryAcquire(counter, 2))
        assertTrue(ShareConnectionCapacity.tryAcquire(counter, 2))
        assertFalse(ShareConnectionCapacity.tryAcquire(counter, 2))
        assertEquals(2, counter.get())
    }
}
