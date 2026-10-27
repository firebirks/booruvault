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
}
