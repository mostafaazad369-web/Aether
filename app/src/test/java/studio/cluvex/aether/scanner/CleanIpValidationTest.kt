package studio.cluvex.aether.scanner
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class CleanIpValidationTest {
    private val a = CleanIpValidation.Candidate("104.16.0.1", 443)
    private val b = CleanIpValidation.Candidate("104.16.0.2", 443)
    @Test fun manualModeStopsAtFirstStableMatchAndStoresMedian() = runBlocking {
        val calls = mutableListOf<List<CleanIpValidation.Candidate>>()
        var confirmation = 0
        val winner = CleanIpValidation.select(listOf(a, b), 300, measure = { batch ->
            calls += batch
            if (batch.size == 2) listOf(100L, 50L) else listOf(listOf(120L, 90L, 110L)[confirmation++])
        })
        assertEquals(a.ip, winner!!.ip); assertEquals(110L, winner.pingMs)
        assertEquals(4, calls.size); assertEquals(3, confirmation)
    }
    @Test fun lowestModeMeasuresAllThenConfirmsLowestStable() = runBlocking {
        val calls = mutableListOf<List<CleanIpValidation.Candidate>>()
        val winner = CleanIpValidation.select(listOf(a, b), null, measure = { batch ->
            calls += batch; batch.map { if (it == a) 8000L else 6000L }
        })
        assertEquals(b.ip, winner!!.ip); assertEquals(6000L, winner.pingMs)
        assertEquals(listOf(a, b), calls.first()); assertEquals(listOf(b), calls[1])
    }
    @Test fun rejectsUnstableFastCandidateThenConfirmsNext() = runBlocking {
        val winner = CleanIpValidation.select(listOf(a, b), null, measure = { batch ->
            if (batch.size == 2) listOf(10L, 20L) else listOf(if (batch.single() == a) -1 else 25)
        })
        assertEquals(b.ip, winner!!.ip); assertEquals(25L, winner.pingMs)
    }
    @Test fun overCeilingAndUnreachableNeverWin() = runBlocking {
        assertNull(CleanIpValidation.select(listOf(a, b), 100, measure = { listOf(101, -1) }))
        assertNull(CleanIpValidation.select(emptyList(), null, measure = { error("no measurements") }))
    }
    @Test fun validationDeduplicatesAndCapsCandidatesBeforeProbing() = runBlocking {
        val candidates = (1..15).map { CleanIpValidation.Candidate("104.16.0.$it", 443) }
        val batches = mutableListOf<List<CleanIpValidation.Candidate>>()
        val progress = mutableListOf<Pair<Int, Int>>()
        val winner = CleanIpValidation.select(
            candidates + candidates.first(),
            null,
            measure = { batch ->
                batches += batch
                batch.map { if (it.ip.endsWith(".10")) 1L else 20L }
            },
            progress = { done, total -> progress += done to total },
        )
        assertEquals("104.16.0.1", winner!!.ip)
        assertEquals(CleanIpValidation.MAX_CANDIDATES, batches.filter { it.size > 1 }.sumOf { it.size })
        assertEquals(listOf(5, 5), batches.filter { it.size > 1 }.map { it.size })
        assertEquals((1..10).map { it to 10 }, progress)
    }

    @Test fun cancellationPropagatesRatherThanApplyingAnything() = runBlocking {
        val task = launch { CleanIpValidation.select(listOf(a), null, measure = { awaitCancellation() }) }
        yield(); task.cancelAndJoin(); assertTrue(task.isCancelled)
    }
}
