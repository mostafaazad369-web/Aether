package studio.cluvex.aether.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessReaperTest {
    @Test
    fun gracefulExitIsConfirmedWithoutForceKill() {
        val process = FakeProcess(exitOnDestroy = true)

        assertTrue(ProcessReaper.terminateAndReap(process, 20, 20))

        assertEquals(1, process.destroyCalls)
        assertEquals(0, process.destroyForciblyCalls)
        assertFalse(process.isAlive)
    }

    @Test
    fun forceKillIsFollowedByAnExitConfirmationWait() {
        val process = FakeProcess(exitOnDestroy = false, exitOnForce = true)

        assertTrue(ProcessReaper.terminateAndReap(process, 1, 20))

        assertEquals(1, process.destroyCalls)
        assertEquals(1, process.destroyForciblyCalls)
        assertFalse(process.isAlive)
    }

    @Test
    fun failedExitConfirmationReturnsFalseAndLeavesProcessAlive() {
        val process = FakeProcess(exitOnDestroy = false, exitOnForce = false)

        assertFalse(ProcessReaper.terminateAndReap(process, 1, 1))

        assertEquals(1, process.destroyCalls)
        assertEquals(1, process.destroyForciblyCalls)
        assertTrue("caller must retain process and its identity lease", process.isAlive)
    }

    @Test
    fun interruptedWaitStillCompletesReapAttemptAndRestoresInterruptFlag() {
        val process = FakeProcess(
            exitOnDestroy = false,
            exitOnForce = true,
            interruptFirstWait = true,
        )
        Thread.interrupted()

        try {
            assertTrue(ProcessReaper.terminateAndReap(process, 20, 20))
            assertTrue("interrupt status must be restored after the reap attempt", Thread.currentThread().isInterrupted)
            assertEquals(1, process.destroyForciblyCalls)
        } finally {
            Thread.interrupted()
        }
    }

    private class FakeProcess(
        private val exitOnDestroy: Boolean,
        private val exitOnForce: Boolean = false,
        private var interruptFirstWait: Boolean = false,
    ) : Process() {
        @Volatile
        private var alive = true

        var destroyCalls = 0
            private set
        var destroyForciblyCalls = 0
            private set

        override fun destroy() {
            destroyCalls++
            if (exitOnDestroy) alive = false
        }

        override fun destroyForcibly(): Process {
            destroyForciblyCalls++
            if (exitOnForce) alive = false
            return this
        }

        override fun isAlive(): Boolean = alive

        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            if (interruptFirstWait) {
                interruptFirstWait = false
                throw InterruptedException("injected interruption")
            }
            return !alive
        }

        override fun waitFor(): Int = if (alive) throw IllegalThreadStateException() else 0

        override fun exitValue(): Int = if (alive) throw IllegalThreadStateException() else 0

        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()

        override fun getInputStream(): InputStream = ByteArrayInputStream(byteArrayOf())

        override fun getErrorStream(): InputStream = ByteArrayInputStream(byteArrayOf())
    }
}
