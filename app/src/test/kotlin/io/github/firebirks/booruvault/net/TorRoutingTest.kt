package io.github.firebirks.booruvault.net

import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.DataInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.URI
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * With Tor on, host names must reach Orbot inside the SOCKS5 request and never be looked up
 * locally. A fake SOCKS5 server records what the client asks it to connect to.
 */
class TorRoutingTest {
    private class Recorded(val addressType: Int, val host: String, val port: Int)

    /** Accepts one SOCKS5 CONNECT, records it, then answers like a tiny HTTP server. */
    private fun fakeSocks(onRequest: (Recorded) -> Unit): ServerSocket {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            server.accept().use { socket ->
                val input = DataInputStream(socket.getInputStream())
                val output = socket.getOutputStream()
                // Greeting: VER, NMETHODS, METHODS → choose "no authentication".
                check(input.readUnsignedByte() == 5)
                repeat(input.readUnsignedByte()) { input.readUnsignedByte() }
                output.write(byteArrayOf(5, 0))
                // Request: VER, CMD, RSV, ATYP, address, port.
                check(input.readUnsignedByte() == 5)
                check(input.readUnsignedByte() == 1)
                input.readUnsignedByte()
                val type = input.readUnsignedByte()
                val host = when (type) {
                    3 -> String(ByteArray(input.readUnsignedByte()).also(input::readFully))
                    1 -> ByteArray(4).also(input::readFully).joinToString(".") { (it.toInt() and 0xff).toString() }
                    else -> "ipv6"
                }
                val port = input.readUnsignedShort()
                onRequest(Recorded(type, host, port))
                output.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0))
                // Then the tunnelled HTTP exchange.
                val reader = socket.getInputStream().bufferedReader()
                while (reader.readLine()?.isNotEmpty() == true) Unit
                output.write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".toByteArray())
                output.flush()
            }
        }
        return server
    }

    private fun client(enabled: () -> Boolean, port: () -> Int) = OkHttpClient.Builder()
        .proxySelector(TorProxySelector(enabled, port))
        .dns(TorAwareDns(enabled))
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    @Test
    fun hostNamesTravelInsideTheSocksRequest() {
        var recorded: Recorded? = null
        val server = fakeSocks { recorded = it }
        server.use {
            val response = client({ true }, { server.localPort })
                .newCall(Request.Builder().url("http://booru.example.invalid/posts.json").build())
                .execute()
            response.use { assertEquals("ok", it.body.string()) }
        }
        val request = recorded!!
        assertEquals("the name must be sent as a domain (ATYP 3), not resolved locally", 3, request.addressType)
        assertEquals("booru.example.invalid", request.host)
        assertEquals(80, request.port)
    }

    @Test
    fun nothingGoesOutWhenOrbotIsDown() {
        // A port nothing listens on: the request must fail, not take another way out.
        val closed = ServerSocket(0).use { it.localPort }
        try {
            client({ true }, { closed }).newCall(Request.Builder().url("http://example.com/").build()).execute().close()
            fail("expected the request to fail")
        } catch (e: IOException) {
            // expected
        }
        val proxies = TorProxySelector({ true }, { closed }).select(URI("https://example.com/"))
        assertEquals(1, proxies.size)
        assertEquals(Proxy.Type.SOCKS, proxies.single().type())
    }

    @Test
    fun localDnsIsRefusedWhileTorIsOn() {
        val dns = TorAwareDns { true }
        try {
            dns.lookup("example.com")
            fail("a local lookup must be refused")
        } catch (e: UnknownHostException) {
            assertTrue(e.message!!.contains("Tor"))
        }
        assertEquals("127.0.0.1", dns.lookup("127.0.0.1").single().hostAddress)
        assertEquals(Proxy.NO_PROXY, TorProxySelector({ false }, { 9050 }).select(URI("https://example.com/")).single())
    }

    @Test
    fun socks5GreetingTellsOrbotFromNothing() {
        // A listener that accepts the greeting.
        val socks = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            socks.accept().use { socket ->
                val input = DataInputStream(socket.getInputStream())
                check(input.readUnsignedByte() == 5)
                repeat(input.readUnsignedByte()) { input.readUnsignedByte() }
                socket.getOutputStream().write(byteArrayOf(5, 0))
                socket.getOutputStream().flush()
            }
        }
        // A listener that speaks something else.
        val other = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) { other.accept().use { it.getOutputStream().write("HTTP/1.1 400\r\n\r\n".toByteArray()) } }
        // A port nobody listens on.
        val closed = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).let { val port = it.localPort; it.close(); port }

        assertTrue(socks5Listening(socks.localPort))
        assertTrue(!socks5Listening(other.localPort))
        assertTrue(!socks5Listening(closed))
        socks.close()
        other.close()
    }

    /** A plain HTTP server that keeps its connections open, so a client can pool them. */
    private fun keepAliveHttp(): ServerSocket {
        val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) {
                    socket.use {
                        val reader = it.getInputStream().bufferedReader()
                        val output = it.getOutputStream()
                        while (true) {
                            val first = reader.readLine() ?: break
                            if (first.isEmpty()) continue
                            while (reader.readLine()?.isNotEmpty() == true) Unit
                            output.write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok".toByteArray())
                            output.flush()
                        }
                    }
                }
            }
        }
        return server
    }

    @Test
    fun aDirectConnectionIsNotReusedOnceTorIsOn() {
        var enabled = false
        val closed = ServerSocket(0).use { it.localPort }
        val client = OkHttpClient.Builder()
            .proxySelector(TorProxySelector({ enabled }, { closed }))
            .dns(TorAwareDns { enabled })
            .addNetworkInterceptor(TorRouteGuard { enabled })
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
        keepAliveHttp().use { server ->
            val request = Request.Builder().url("http://127.0.0.1:${server.localPort}/").build()
            // Opened directly, and left in the pool.
            client.newCall(request).execute().use { assertEquals("ok", it.body.string()) }
            enabled = true
            try {
                client.newCall(request).execute().close()
                fail("the pooled direct connection must not carry a request while Tor is on")
            } catch (e: IOException) {
                assertTrue(e is TorUnreachableException || e.cause is TorUnreachableException || e.message!!.contains("Orbot") || e.message!!.contains("refused"))
            }
        }
    }

    @Test
    fun nothingButTheExitCheckGoesOutBeforeItPasses() {
        var verified = false
        var blocked = 0
        // The check's host name resolves to the local server, so the gate sees the real name.
        val client = OkHttpClient.Builder()
            .addInterceptor(TorExitGate({ true }, { verified }) { blocked++ })
            .dns { listOf(InetAddress.getByName("127.0.0.1")) }
            .build()
        keepAliveHttp().use { server ->
            val other = Request.Builder().url("http://booru.example.invalid:${server.localPort}/").build()
            try {
                client.newCall(other).execute().close()
                fail("a request must wait for the exit check")
            } catch (e: TorUnverifiedException) {
                // expected
            }
            assertEquals(1, blocked)
            // The check itself is the one request that may go while unverified.
            val check = Request.Builder().url("http://${TorManager.VERIFY_HOST}:${server.localPort}/api/ip").build()
            client.newCall(check).execute().use { assertEquals("ok", it.body.string()) }
            verified = true
            client.newCall(other).execute().use { assertEquals("ok", it.body.string()) }
            assertEquals(1, blocked)
        }
    }
}
