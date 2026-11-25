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
        ServerSocket(0).use { server ->
            val source = Socket("127.0.0.1", server.localPort)
            val destination = server.accept()
            try {
                val transferred = AtomicLong(0L)
                val worker = Thread {
                    ShareSocketRelay.pipe(source, destination, transferred)
                }
                worker.start()

                source.getOutputStream().write("request".toByteArray())
                source.shutdownOutput()

                val received = destination.getInputStream().readBytes()
                assertEquals("request", String(received))
                destination.getOutputStream().write("response".toByteArray())
                destination.getOutputStream().flush()

                val response = ByteArray(8)
                var offset = 0
                while (offset < response.size) {
                    val count = source.getInputStream().read(response, offset, response.size - offset)
                    assertTrue("reverse stream ended prematurely", count >= 0)
                    offset += count
                }
                assertEquals("response", String(response))
                worker.join(1_000)
                assertTrue("forward relay worker should finish after source EOF", !worker.isAlive)
                assertEquals(7L, transferred.get())
            } finally {
                source.close()
                destination.close()
            }
        }
    }
}
