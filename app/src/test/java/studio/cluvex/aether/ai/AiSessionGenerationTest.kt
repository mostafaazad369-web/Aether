package studio.cluvex.aether.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiSessionGenerationTest {

    @Test
    fun initialGenerationIsCurrent() {
        val generation = AiSessionGeneration()
        val initial = generation.current()

        assertTrue(generation.isCurrent(initial))
    }

    @Test
    fun advancingInvalidatesEveryEarlierOperation() {
        val generation = AiSessionGeneration()
        val oldOperation = generation.current()

        val next = generation.advance()

        assertTrue(next > oldOperation)
        assertFalse(generation.isCurrent(oldOperation))
        assertTrue(generation.isCurrent(next))
    }

    @Test
    fun eachCredentialChangeCreatesANewGeneration() {
        val generation = AiSessionGeneration()
        val first = generation.current()
        val second = generation.advance()
        val third = generation.advance()

        assertEquals(first + 1, second)
        assertEquals(second + 1, third)
        assertFalse(generation.isCurrent(first))
        assertFalse(generation.isCurrent(second))
        assertTrue(generation.isCurrent(third))
    }

    @Test
    fun currentGenerationPublishesResult() {
        val generation = AiSessionGeneration()
        val token = generation.current()
        var published = false

        val accepted = generation.runIfCurrent(token) { published = true }

        assertTrue(accepted)
        assertTrue(published)
    }

    @Test
    fun staleGenerationCannotPublishResult() {
        val generation = AiSessionGeneration()
        val stale = generation.current()
        generation.advance()
        var published = false

        val accepted = generation.runIfCurrent(stale) { published = true }

        assertFalse(accepted)
        assertFalse(published)
    }
}
