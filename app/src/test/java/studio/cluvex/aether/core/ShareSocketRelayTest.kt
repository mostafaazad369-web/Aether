package studio.cluvex.aether.core

import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareSocketRelayTest {
    @Test
    fun interruptedJoinClosesOwnerEndpointsAndRestoresInterruptStatus() {
        val releaseWorker = CountDownLatch(1)
        val callerStarted = CountDownLatch(1)
        val interruptionCleanup = AtomicBoolean(false)
        val interruptRestored = AtomicBoolean(false)
        val worker = Thread { releaseWorker.await() }
        worker.start()

        val caller = Thread {
            callerStarted.countDown()
            ShareSocketRelay.joinUntilFinished(worker) {
                interruptionCleanup.set(true)
                releaseWorker.countDown()
            }
            interruptRestored.set(Thread.currentThread().isInterrupted)
        }
        caller.start()
        callerStarted.await()
        caller.interrupt()
        caller.join(1_000)
        worker.join(1_000)

        assertTrue("join caller should finish", !caller.isAlive)
        assertTrue("worker should be released by interruption cleanup", !worker.isAlive)
        assertTrue("owner cleanup should run", interruptionCleanup.get())
        assertTrue("interrupt status should be restored", interruptRestored.get())
    }

    @Test
    fun sourceHalfCloseDoesNotDisableReverseDirection() {
        ServerSocket(0).use { clientListener ->
            ServerSocket(0).use { upstreamListener ->
                val clientPeer = Socket("127.0.0.1", clientListener.localPort)
                val bridgeClient = clientListener.accept()
                val upstreamPeer = Socket("127.0.0.1", upstreamListener.localPort)
                val bridgeUpstream = upstreamListener.accept()
                try {
                    val uploaded = AtomicLong(0L)
                    val downloaded = AtomicLong(0L)
                    val forward = Thread {
                        ShareSocketRelay.pipe(bridgeClient, bridgeUpstream, uploaded)
                    }
                    val reverse = Thread {
                        ShareSocketRelay.pipe(bridgeUpstream, bridgeClient, downloaded)
                    }
                    forward.start()
                    reverse.start()

                    clientPeer.getOutputStream().write("request".toByteArray())
                    clientPeer.shutdownOutput()

                    assertEquals("request", String(upstreamPeer.getInputStream().readBytes()))
                    upstreamPeer.getOutputStream().write("response".toByteArray())
                    upstreamPeer.getOutputStream().flush()
                    upstreamPeer.shutdownOutput()

                    assertEquals("response", String(clientPeer.getInputStream().readBytes()))
                    forward.join(1_000)
                    reverse.join(1_000)
                    assertTrue("forward relay worker should finish", !forward.isAlive)
                    assertTrue("reverse relay worker should finish", !reverse.isAlive)
                    assertEquals(7L, uploaded.get())
                    assertEquals(8L, downloaded.get())
                } finally {
                    clientPeer.close()
                    bridgeClient.close()
                    upstreamPeer.close()
                    bridgeUpstream.close()
                }
            }
        }
    }
}
