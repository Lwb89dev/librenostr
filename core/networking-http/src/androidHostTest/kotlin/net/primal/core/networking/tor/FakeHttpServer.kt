package net.primal.core.networking.tor

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * A minimal HTTP/1.1 server for tests, with keep-alive, that counts the requests it receives.
 *
 * Kept deliberately small instead of pulling in a mock web server: the only questions asked of it are
 * "did a request reach me directly?" ([requestCount]) and "does my connection stay open for reuse?",
 * and both need to be exactly right rather than convenient.
 */
class FakeHttpServer : Closeable {

    private val server = ServerSocket(0)
    private val workers = Executors.newCachedThreadPool { runnable -> Thread(runnable).apply { isDaemon = true } }
    private val bodies = ConcurrentLinkedQueue<String>()
    private val received = AtomicInteger(0)

    val port: Int get() = server.localPort
    val requestCount: Int get() = received.get()

    /** A URL that reaches this server by name, so the client has to resolve it (or hand it to a proxy). */
    fun url(): String = "http://localhost:$port/"

    /** The body of the next response; a server with nothing queued answers "direct". */
    fun enqueue(body: String) {
        bodies.add(body)
    }

    init {
        workers.execute { acceptLoop() }
    }

    private fun acceptLoop() {
        while (!server.isClosed) {
            val client = try {
                server.accept()
            } catch (ignored: IOException) {
                return // the server was closed
            }
            workers.execute { serveQuietly(client) }
        }
    }

    /** A client that hangs up mid-request is normal in these tests and must not fail them. */
    private fun serveQuietly(client: Socket) {
        try {
            serve(client)
        } catch (ignored: IOException) {
            // Nothing to report: the test asserts on requestCount.
        }
    }

    private fun serve(client: Socket) {
        client.use {
            val input = it.getInputStream()
            val output = it.getOutputStream()
            while (true) {
                val head = readRequestHead(input) ?: return
                received.incrementAndGet()
                if (head.contains("Upgrade: websocket", ignoreCase = true)) {
                    acceptWebSocket(head, input, output)
                    return
                }
                val body = bodies.poll() ?: DEFAULT_BODY
                output.write("HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\n\r\n$body".toByteArray())
                output.flush()
            }
        }
    }

    /** Completes the WebSocket handshake and then holds the connection open until the client closes it. */
    private fun acceptWebSocket(head: String, input: InputStream, output: OutputStream) {
        val key = head.lineSequence().first { it.startsWith("Sec-WebSocket-Key:", ignoreCase = true) }
            .substringAfter(":").trim()
        val digest = MessageDigest.getInstance("SHA-1").digest((key + WEBSOCKET_GUID).toByteArray())
        val accept = Base64.getEncoder().encodeToString(digest)
        output.write(
            ("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                "Sec-WebSocket-Accept: $accept\r\n\r\n").toByteArray(),
        )
        output.flush()
        while (input.read() >= 0) {
            // Frames are ignored; only the lifetime of the connection matters.
        }
    }

    /** Reads up to the blank line ending a request head; null when the client closed the connection. */
    private fun readRequestHead(input: InputStream): String? {
        val head = StringBuilder()
        while (!head.endsWith(HEAD_END)) {
            val next = input.read()
            if (next < 0) return null
            head.append(next.toChar())
        }
        return head.toString()
    }

    override fun close() {
        server.close()
        workers.shutdownNow()
    }

    private companion object {
        const val HEAD_END = "\r\n\r\n"
        const val DEFAULT_BODY = "direct"
        const val WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
    }
}
