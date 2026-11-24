package studio.cluvex.aether.core

import java.util.concurrent.TimeUnit

/**
 * Terminates a child process and confirms that the OS has observed its exit.
 *
 * Keeping this policy separate from AetherProcess makes the failure paths
 * testable with a deterministic Process double; callers must retain ownership
 * of the process and any associated secrets when this returns false.
 */
internal object ProcessReaper {
    const val DEFAULT_GRACEFUL_EXIT_MS = 250L
    const val DEFAULT_FORCED_EXIT_CONFIRM_MS = 2_000L

    fun terminateAndReap(
        process: Process,
        gracefulExitMs: Long = DEFAULT_GRACEFUL_EXIT_MS,
        forcedExitConfirmMs: Long = DEFAULT_FORCED_EXIT_CONFIRM_MS,
    ): Boolean {
        require(gracefulExitMs >= 0L) { "gracefulExitMs must not be negative" }
        require(forcedExitConfirmMs >= 0L) { "forcedExitConfirmMs must not be negative" }

        runCatching { process.destroy() }
        if (waitForExitUninterruptibly(process, gracefulExitMs)) return true

        runCatching { process.destroyForcibly() }
        return waitForExitUninterruptibly(process, forcedExitConfirmMs)
    }

    private fun waitForExitUninterruptibly(process: Process, timeoutMs: Long): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        var interrupted = false
        try {
            while (true) {
                val remainingNanos = deadline - System.nanoTime()
                if (remainingNanos <= 0L) return !process.isAlive
                try {
                    if (process.waitFor(remainingNanos, TimeUnit.NANOSECONDS)) {
                        return true
                    }
                    return !process.isAlive
                } catch (_: InterruptedException) {
                    // Finish the bounded reap attempt before restoring cancellation.
                    interrupted = true
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }
}
