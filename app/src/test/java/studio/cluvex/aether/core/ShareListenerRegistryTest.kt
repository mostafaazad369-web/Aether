package studio.cluvex.aether.core

import java.net.DatagramSocket
import java.net.Socket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ShareListenerRegistryTest {
    private class FakeListener(val name: String) {
        var closeCount = 0

        fun close() {
            closeCount++
        }
    }

    @Test
    fun retainClosesOnlyListenersThatAreNoLongerWanted() {
        val registry = ShareListenerRegistry<FakeListener> { it.close() }
        val keep = FakeListener("keep")
        val remove = FakeListener("remove")
        registry.add(keep)
        registry.add(remove)

        registry.retain { it.name == "keep" }

        assertEquals(listOf(keep), registry.snapshot())
        assertEquals(0, keep.closeCount)
        assertEquals(1, remove.closeCount)
    }

    @Test
    fun closeAllClosesEachOwnedListenerExactlyOnceAndIsIdempotent() {
        val registry = ShareListenerRegistry<FakeListener> { it.close() }
        val first = FakeListener("first")
        val second = FakeListener("second")
        registry.add(first)
        registry.add(second)

        registry.closeAll()
        registry.closeAll()

        assertTrue(registry.isEmpty())
        assertEquals(1, first.closeCount)
        assertEquals(1, second.closeCount)
    }

    @Test
    fun detachWhereTransfersOwnershipWithoutClosingUntilBatchRuns() {
        val registry = ShareListenerRegistry<FakeListener> { it.close() }
        val keep = FakeListener("keep")
        val detached = FakeListener("detached")
        registry.add(keep)
        registry.add(detached)

        val batch = registry.detachWhere { it.name == "keep" }

        assertEquals(listOf(keep), registry.snapshot())
        assertEquals(1, batch.size)
        assertEquals(0, detached.closeCount)
        assertEquals(true, batch.close())
        assertEquals(1, detached.closeCount)
    }

    @Test
    fun detachedBatchClosesEachEntryExactlyOnceWhenCloseIsRepeated() {
        val registry = ShareListenerRegistry<FakeListener> { it.close() }
        val first = FakeListener("first")
        val second = FakeListener("second")
        registry.add(first)
        registry.add(second)

        val batch = registry.detachAll()
        assertTrue(registry.isEmpty())
        assertEquals(0, first.closeCount)
        assertEquals(0, second.closeCount)

        assertEquals(true, batch.close())
        assertEquals(true, batch.close())

        assertEquals(1, first.closeCount)
        assertEquals(1, second.closeCount)
    }

    @Test
    fun concurrentDuplicateCloseWaitsForFirstCloseToComplete() {
        val closeEntered = CountDownLatch(1)
        val allowCloseToFinish = CountDownLatch(1)
        val registry = ShareListenerRegistry<FakeListener> {
            closeEntered.countDown()
            check(allowCloseToFinish.await(1, TimeUnit.SECONDS))
            it.close()
        }
        val listener = FakeListener("once")
        registry.add(listener)
        val batch = registry.detachAll()
        val secondStarted = CountDownLatch(1)
        val firstResult = java.util.concurrent.atomic.AtomicReference<Boolean?>(null)
        val secondResult = java.util.concurrent.atomic.AtomicReference<Boolean?>(null)
        val first = Thread { firstResult.set(batch.close()) }
        val second = Thread {
            secondStarted.countDown()
            secondResult.set(batch.close())
        }

        first.start()
        assertTrue(closeEntered.await(1, TimeUnit.SECONDS))
        second.start()
        assertTrue(secondStarted.await(1, TimeUnit.SECONDS))
        assertEquals(null, secondResult.get())
        allowCloseToFinish.countDown()
        first.join(1_000)
        second.join(1_000)

        assertEquals(false, first.isAlive)
        assertEquals(false, second.isAlive)
        assertEquals(true, firstResult.get())
        assertEquals(true, secondResult.get())
        assertEquals(1, listener.closeCount)
    }

    @Test
    fun closeBatchReportsFailureAndDoesNotRetryFailedCallback() {
        val attempts = java.util.concurrent.atomic.AtomicInteger(0)
        val registry = ShareListenerRegistry<FakeListener> {
            attempts.incrementAndGet()
            error("simulated close failure")
        }
        registry.add(FakeListener("broken"))
        val batch = registry.detachAll()

        assertEquals(false, batch.close())
        assertEquals(false, batch.close())
        assertEquals(1, attempts.get())
    }

    @Test
    fun replacementWorkCannotPassSynchronousCloseBatchBarrierEarly() {
        val closeEntered = CountDownLatch(1)
        val allowCloseToFinish = CountDownLatch(1)
        val registry = ShareListenerRegistry<FakeListener> {
            closeEntered.countDown()
            check(allowCloseToFinish.await(1, TimeUnit.SECONDS))
            it.close()
        }
        val oldListener = FakeListener("old")
        registry.add(oldListener)
        val batch = registry.detachAll()
        val replacementStarted = java.util.concurrent.atomic.AtomicBoolean(false)
        val worker = Thread {
            batch.close()
            replacementStarted.set(true)
        }

        worker.start()
        assertTrue(closeEntered.await(1, TimeUnit.SECONDS))
        assertEquals(false, replacementStarted.get())
        allowCloseToFinish.countDown()
        worker.join(1_000)

        assertEquals(false, worker.isAlive)
        assertEquals(true, replacementStarted.get())
        assertEquals(1, oldListener.closeCount)
    }

    @Test
    fun retainedListenersCanBeOrderedWithoutChangingOwnership() {
        val registry = ShareListenerRegistry<FakeListener> { it.close() }
        val later = FakeListener("later")
        val earlier = FakeListener("earlier")
        registry.add(later)
        registry.add(earlier)

        registry.sortBy { it.name }

        assertEquals(listOf(earlier, later), registry.snapshot())
        assertEquals(0, later.closeCount)
        assertEquals(0, earlier.closeCount)
    }

    @Test
    fun lanTeardownClosesLanClientsButKeepsLoopbackClientsOwned() {
        data class Client(val lan: Boolean, var closeCount: Int = 0)
        val registry = ShareListenerRegistry<Client> { it.closeCount++ }
        val loopback = Client(lan = false)
        val lan = Client(lan = true)
        registry.add(loopback)
        registry.add(lan)

        registry.retain { !it.lan }

        assertEquals(listOf(loopback), registry.snapshot())
        assertEquals(0, loopback.closeCount)
        assertEquals(1, lan.closeCount)
    }

    @Test
    fun closingClientOwnershipClosesClientAndEveryRegisteredUpstream() {
        class TrackingSocket : Socket() {
            var closes = 0
            override fun close() {
                closes++
            }
        }

        val client = TrackingSocket()
        val firstUpstream = TrackingSocket()
        val secondUpstream = TrackingSocket()
        val owner = ShareBridge.ActiveClient(client, viaAddress = null)
        owner.upstreams.add(firstUpstream)
        owner.upstreams.add(secondUpstream)

        owner.close()
        owner.close()

        assertEquals(1, client.closes)
        assertEquals(1, firstUpstream.closes)
        assertEquals(1, secondUpstream.closes)
    }

    @Test
    fun activeClientCloseSurfacesFailureAfterAttemptingEveryOwnedSocket() {
        class FailingSocket : Socket() {
            var closeAttempts = 0
            override fun close() {
                closeAttempts++
                throw java.io.IOException("simulated close failure")
            }
        }
        class TrackingSocket : Socket() {
            var closeAttempts = 0
            override fun close() {
                closeAttempts++
            }
        }

        val client = FailingSocket()
        val upstream = TrackingSocket()
        val owner = ShareBridge.ActiveClient(client, viaAddress = null)
        owner.upstreams.add(upstream)

        var failed = false
        try {
            owner.close()
        } catch (_: IllegalStateException) {
            failed = true
        }

        assertTrue("close failure must be visible to the teardown batch", failed)
        assertEquals(1, upstream.closeAttempts)
        assertEquals(1, client.closeAttempts)
        // Ownership close is exactly-once even when a close callback fails.
        try {
            owner.close()
        } catch (_: IllegalStateException) {
            throw AssertionError("a completed close attempt must not be repeated")
        }
        assertEquals(1, upstream.closeAttempts)
        assertEquals(1, client.closeAttempts)
    }

    @Test
    fun closingClientOwnershipClosesRegisteredUdpEndpoints() {
        class TrackingDatagramSocket : DatagramSocket() {
            var closes = 0
            override fun close() {
                closes++
                super.close()
            }
        }

        val client = Socket()
        val udpClientSide = TrackingDatagramSocket()
        val udpUpstreamSide = TrackingDatagramSocket()
        val owner = ShareBridge.ActiveClient(client, viaAddress = null)
        owner.datagrams.add(udpClientSide)
        owner.datagrams.add(udpUpstreamSide)

        owner.close()
        owner.close()

        assertEquals(1, udpClientSide.closes)
        assertEquals(1, udpUpstreamSide.closes)
        assertTrue(udpClientSide.isClosed)
        assertTrue(udpUpstreamSide.isClosed)
    }
}
