package studio.cluvex.aether.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareCommandGenerationTest {
    @Test
    fun queuedStartIsInvalidatedByALaterStop() {
        val commands = ShareCommandGeneration()

        val queuedStart = commands.reserve()
        val stop = commands.reserve()

        assertFalse(commands.isCurrent(queuedStart))
        assertTrue(commands.isCurrent(stop))
    }

    @Test
    fun newerStartSupersedesAnEarlierStop() {
        val commands = ShareCommandGeneration()

        val stop = commands.reserve()
        val start = commands.reserve()

        assertFalse(commands.isCurrent(stop))
        assertTrue(commands.isCurrent(start))
    }

    @Test
    fun staleStopCompletionCannotActOnANewerStart() {
        val commands = ShareCommandGeneration()

        val stop = commands.reserve()
        val start = commands.reserve()

        // The asynchronous stop worker checks this immediately before cleanup.
        assertFalse(commands.isCurrent(stop))
        assertTrue(commands.isCurrent(start))
    }
}
