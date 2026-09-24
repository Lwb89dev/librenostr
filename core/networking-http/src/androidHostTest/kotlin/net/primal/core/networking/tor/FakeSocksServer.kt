package net.primal.core.networking.tor

import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/** What a client asked a SOCKS5 proxy to connect to. [addressType] is 1 (IPv4), 3 (domain name) or 4 (IPv6). */
data class SocksRequest(val addressType: Int, val host: String, val port: Int) {
    val isDomainName get() = addressType == ADDRESS_TYPE_DOMAIN

    companion object {
        const val ADDRESS_TYPE_DOMAIN = 3
    }
}

/**
 * A minimal SOCKS5 proxy for tests that records what it is asked to connect to and answers every
 * tunnel with a fixed `200 ok` HTTP response.
 *
 * What it records is the point: a client that resolved the name itself would ask for an IP address
 * (type 1), one that hands the name over unresolved asks for a domain name (type 3). That is the
 * difference between a Tor route that leaks a DNS query and one that does not.
 */
class FakeSocksServer : Closeable {

    private val server = ServerSocket(0, BACKLOG, InetAddress.getLoopbackAddress())
    private val workers = Executors.newCachedThreadPool { runnable -> Thread(runnable).apply { isDaemon = true } }
    private val recorded = CopyOnWriteArrayList<SocksRequest>()

    val port: Int get() = server.localPort
    val requests: List<SocksRequest> get() = recorded.toList()

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

    /** A client that hangs up mid-handshake is normal in these tests and must not fail them. */
    private fun serveQuietly(client: Socket) {
        try {
            serve(client)
        } catch (ignored: IOException) {
            // Nothing to report: the test asserts on what was recorded.
        }
    }

    private fun serve(client: Socket) {
        client.use {
            val input = DataInputStream(it.getInputStream())
            val output = it.getOutputStream()

            input.readUnsignedByte() // version
            input.readFully(ByteArray(input.readUnsignedByte())) // offered auth methods
            output.write(byteArrayOf(SOCKS_VERSION, NO_AUTH))

            input.readFully(ByteArray(REQUEST_HEADER_SIZE)) // version, command, reserved
            recorded += readRequest(input)
            output.write(byteArrayOf(SOCKS_VERSION, 0, 0, 1, 0, 0, 0, 0, 0, 0))

            discardHttpRequestHead(input)
            output.write(HTTP_RESPONSE.toByteArray())
            output.flush()
        }
    }

    private fun readRequest(input: DataInputStream): SocksRequest {
        val addressType = input.readUnsignedByte()
        val host = when (addressType) {
            ADDRESS_TYPE_IPV4 -> input.readBytes(IPV4_SIZE).joinToString(".") { it.unsigned().toString() }
            SocksRequest.ADDRESS_TYPE_DOMAIN -> String(input.readBytes(input.readUnsignedByte()))
            else -> input.readBytes(IPV6_SIZE).joinToString(":") { it.unsigned().toString(radix = 16) }
        }
        return SocksRequest(addressType = addressType, host = host, port = input.readUnsignedShort())
    }

    private fun DataInputStream.readBytes(count: Int) = ByteArray(count).also { readFully(it) }

    private fun Byte.unsigned() = toInt() and BYTE_MASK

    private fun discardHttpRequestHead(input: DataInputStream) {
        var matched = 0
        while (matched < HEAD_END.length) {
            val next = input.read()
            if (next < 0) return
            matched = if (next == HEAD_END[matched].code) matched + 1 else if (next == HEAD_END[0].code) 1 else 0
        }
    }

    override fun close() {
        server.close()
        workers.shutdownNow()
    }

    private companion object {
        const val BACKLOG = 50
        const val SOCKS_VERSION: Byte = 5
        const val NO_AUTH: Byte = 0
        const val REQUEST_HEADER_SIZE = 3
        const val ADDRESS_TYPE_IPV4 = 1
        const val IPV4_SIZE = 4
        const val IPV6_SIZE = 16
        const val BYTE_MASK = 0xff
        const val HEAD_END = "\r\n\r\n"
        const val HTTP_RESPONSE = "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok"
    }
}
