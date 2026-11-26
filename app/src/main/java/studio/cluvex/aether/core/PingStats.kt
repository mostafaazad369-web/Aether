package studio.cluvex.aether.core

import kotlin.math.abs
import kotlin.math.roundToLong

/** Bounded latency summary calculated from real probe samples; negative samples are losses. */
object PingStats {
    data class Summary(
        val samples: Int = 0,
        val answered: Int = 0,
        val minMs: Long = -1,
        val maxMs: Long = -1,
        val avgMs: Long = -1,
        val jitterMs: Long = -1,
        val lossPercent: Int = 0,
        val lastMs: Long = -1,
    )

    fun of(samples: List<Long>): Summary {
        if (samples.isEmpty()) return Summary()
        val ok = samples.filter { it >= 0L }
        val loss = ((samples.size - ok.size) * 100.0 / samples.size).roundToLong().toInt()
        if (ok.isEmpty()) return Summary(samples = samples.size, lossPercent = loss, lastMs = samples.last())
        val jitter = if (ok.size < 2) 0L
            else (ok.zipWithNext { a, b -> abs(b - a) }.sum().toDouble() / (ok.size - 1)).roundToLong()
        return Summary(
            samples = samples.size,
            answered = ok.size,
            minMs = ok.minOrNull() ?: -1L,
            maxMs = ok.maxOrNull() ?: -1L,
            avgMs = (ok.sum().toDouble() / ok.size).roundToLong(),
            jitterMs = jitter,
            lossPercent = loss,
            lastMs = samples.last(),
        )
    }
}
