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
    @Test
    fun laterLanDisableInvalidatesQueuedLanEnable() {
        val lanRequests = ShareCommandGeneration()

        val queuedEnable = lanRequests.reserve()
        val disable = lanRequests.reserve()

        assertFalse(lanRequests.isCurrent(queuedEnable))
        assertTrue(lanRequests.isCurrent(disable))
    }

    @Test
    fun laterConfigurationRefreshSupersedesAnOlderRefresh() {
        val configRefreshes = ShareCommandGeneration()

        val modeChange = configRefreshes.reserve()
        val routeChange = configRefreshes.reserve()

        assertFalse(configRefreshes.isCurrent(modeChange))
        assertTrue(configRefreshes.isCurrent(routeChange))
    }

    @Test
    fun stoppingInvalidatesPendingLanAndConfigurationWorkers() {
        val lanRequests = ShareCommandGeneration()
        val configRefreshes = ShareCommandGeneration()

        val pendingEnable = lanRequests.reserve()
        val pendingModeRefresh = configRefreshes.reserve()

        lanRequests.reserve()
        configRefreshes.reserve()

        assertFalse(lanRequests.isCurrent(pendingEnable))
        assertFalse(configRefreshes.isCurrent(pendingModeRefresh))
    }

}
