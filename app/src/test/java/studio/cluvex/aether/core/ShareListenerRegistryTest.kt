package studio.cluvex.aether.core

import java.net.Socket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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

        assertEquals(2, client.closes)
        assertEquals(2, firstUpstream.closes)
        assertEquals(2, secondUpstream.closes)
    }
}
