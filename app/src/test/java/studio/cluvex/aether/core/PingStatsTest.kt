package studio.cluvex.aether.core

import org.junit.Assert.assertEquals
import org.junit.Test

class PingStatsTest {
    @Test fun emptyHistoryHasNoMeasurements() {
        assertEquals(PingStats.Summary(), PingStats.of(emptyList()))
    }

    @Test fun computesLatencyAndLossFromActualSamples() {
        val result = PingStats.of(listOf(20L, 40L, -1L, 30L))
        assertEquals(4, result.samples)
        assertEquals(3, result.answered)
        assertEquals(20L, result.minMs)
        assertEquals(40L, result.maxMs)
        assertEquals(30L, result.avgMs)
        assertEquals(15L, result.jitterMs)
        assertEquals(25, result.lossPercent)
        assertEquals(30L, result.lastMs)
    }

    @Test fun allLostHistoryDoesNotInventLatency() {
        val result = PingStats.of(listOf(-1L, -1L))
        assertEquals(2, result.samples)
        assertEquals(0, result.answered)
        assertEquals(-1L, result.minMs)
        assertEquals(-1L, result.avgMs)
        assertEquals(100, result.lossPercent)
    }
}
