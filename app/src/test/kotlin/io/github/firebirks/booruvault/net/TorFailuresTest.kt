package io.github.firebirks.booruvault.net

import io.github.firebirks.booruvault.util.Problem
import io.github.firebirks.booruvault.util.toProblem
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.DataInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * With Tor on, a request that fails is put down to Tor being unreachable only when Orbot's port
 * no longer answers. A circuit that is slow to build, or a site Tor cannot reach, leaves Orbot
 * running: telling the user to check it would send them looking in the wrong place.
 */
class TorFailuresTest {
    /** How a fake Orbot answers the CONNECT of a request. */
    private sealed interface Connect {
        /** Never answers, as Orbot does while it builds a circuit that does not come. */
        data object Hold : Connect

        /** Answers after [ms], then serves a tiny page through the tunnel. */
        data class After(val ms: Long) : Connect

        /** Answers with a SOCKS5 failure code, as Tor does about a destination it cannot reach. */
        data class Fails(val reply: Int) : Connect
    }

    /** A SOCKS5 listener that greets every client, the port probe included, and treats each CONNECT as told. */
    private fun fakeOrbot(connect: Connect): ServerSocket {
        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) {
                    runCatching {
                        socket.use {
                            val input = DataInputStream(it.getInputStream())
                            val output = it.getOutputStream()
                            check(input.readUnsignedByte() == 5)
                            repeat(input.readUnsignedByte()) { input.readUnsignedByte() }
                            output.write(byteArrayOf(5, 0))
                            output.flush()
                            // The probe stops after the greeting; a request goes on to its CONNECT.
                            if (input.read() != 5) return@use
                            input.readUnsignedByte()
                            input.readUnsignedByte()
                            when (input.readUnsignedByte()) {
                                3 -> input.skipBytes(input.readUnsignedByte())
                                1 -> input.skipBytes(4)
                                else -> input.skipBytes(16)
                            }
                            input.readUnsignedShort()
                            when (connect) {
                                Connect.Hold -> input.readBytes() // until the client gives up
                                is Connect.Fails -> output.write(byteArrayOf(5, connect.reply.toByte(), 0, 1, 0, 0, 0, 0, 0, 0))
                                is Connect.After -> {
                                    Thread.sleep(connect.ms)
                                    output.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0))
                                    val reader = it.getInputStream().bufferedReader()
                                    while (reader.readLine()?.isNotEmpty() == true) Unit
                                    output.write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".toByteArray())
                                }
                            }
                            output.flush()
                        }
                    }
                }
            }
        }
        return server
    }

    private fun client(port: Int, torConnectTimeoutMs: Int, clientConnectTimeoutMs: Long = 5_000) = OkHttpClient.Builder()
        .proxySelector(TorProxySelector({ true }, { port }))
        .dns(TorAwareDns { true })
        .addInterceptor(TorFailures({ true }, { port }, torConnectTimeoutMs))
        .connectTimeout(clientConnectTimeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private fun failure(client: OkHttpClient): IOException {
        try {
            client.newCall(Request.Builder().url("http://booru.example.invalid/posts.json").build()).execute().close()
        } catch (e: IOException) {
            return e
        }
        fail("the request was expected to fail")
        error("unreachable")
    }

    @Test
    fun `a circuit too slow to build is a timeout, not Tor unreachable`() {
        fakeOrbot(Connect.Hold).use { orbot ->
            val e = failure(client(orbot.localPort, torConnectTimeoutMs = 300))
            assertTrue(e.toString(), e !is TorUnreachableException)
            assertEquals(Problem.Code.TIMEOUT, e.toProblem().code)
        }
    }

    @Test
    fun `a port nobody answers on is Tor unreachable`() {
        val closed = ServerSocket(0).use { it.localPort }
        val e = failure(client(closed, torConnectTimeoutMs = 300))
        assertTrue(e.toString(), e is TorUnreachableException)
        assertEquals(Problem.Code.TOR_UNREACHABLE, e.toProblem().code)
    }

    @Test
    fun `a site Tor cannot reach is not Tor unreachable`() {
        // REP 4: host unreachable, said by Orbot about the destination, not about itself.
        fakeOrbot(Connect.Fails(4)).use { orbot ->
            val e = failure(client(orbot.localPort, torConnectTimeoutMs = 2_000))
            assertTrue(e.toString(), e !is TorUnreachableException)
            assertEquals(Problem.Code.NETWORK, e.toProblem().code)
        }
    }

    @Test
    fun `a circuit slower than the client's own connect timeout is waited for through Tor`() {
        fakeOrbot(Connect.After(ms = 800)).use { orbot ->
            val client = client(orbot.localPort, torConnectTimeoutMs = 5_000, clientConnectTimeoutMs = 200)
            client.newCall(Request.Builder().url("http://booru.example.invalid/posts.json").build()).execute().use {
                assertEquals("ok", it.body.string())
            }
        }
    }

    @Test
    fun `with Tor off a failure passes as it is`() {
        val closed = ServerSocket(0).use { it.localPort }
        val client = OkHttpClient.Builder()
            .addInterceptor(TorFailures({ false }, { closed }, 300))
            .connectTimeout(2, TimeUnit.SECONDS)
            .build()
        try {
            client.newCall(Request.Builder().url("http://127.0.0.1:$closed/").build()).execute().close()
            fail("nothing listens there")
        } catch (e: IOException) {
            assertTrue(e.toString(), e !is TorUnreachableException)
        }
    }
}
