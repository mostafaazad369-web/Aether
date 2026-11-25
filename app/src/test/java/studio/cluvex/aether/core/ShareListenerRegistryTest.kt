package studio.cluvex.aether.core

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
}
