package studio.cluvex.aether.core

import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareSocketRelayTest {
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
