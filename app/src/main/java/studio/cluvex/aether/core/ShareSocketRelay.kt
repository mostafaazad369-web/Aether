package studio.cluvex.aether.core

import java.net.Socket
import java.util.concurrent.atomic.AtomicLong

/**
 * One direction of a full-duplex TCP relay. EOF is propagated as a half-close
 * on the destination output only; the source input remains usable for the
 * opposite direction until its own peer closes or the owner tears down sockets.
 */
internal object ShareSocketRelay {
    fun pipe(from: Socket, to: Socket, counter: AtomicLong) {
        val buffer = ByteArray(16 * 1024)
        try {
            val input = from.getInputStream()
            val output = to.getOutputStream()
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                output.write(buffer, 0, n)
                output.flush()
                counter.addAndGet(n.toLong())
            }
        } catch (_: Exception) {
        } finally {
            runCatching { to.shutdownOutput() }
        }
    }
}
