package io.github.firebirks.booruvault.net

import okhttp3.Call
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Until the user has chosen between Tor and a direct connection, nothing leaves the app: whatever
 * asks (a search, a suggestion, a thumbnail, a download), no name is looked up and no connection
 * is made. A local server counts the connections it gets; a resolver and a listener count lookups
 * and connection attempts.
 */
class RouteChoiceTest {
    /** Answers every connection with a tiny HTTP response, and counts them. */
    private class CountingServer : AutoCloseable {
        val accepted = AtomicInteger()
        private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = server.localPort

        init {
            thread(isDaemon = true) {
                while (!server.isClosed) {
                    val socket = runCatching { server.accept() }.getOrNull() ?: break
                    accepted.incrementAndGet()
                    socket.use {
                        val reader = it.getInputStream().bufferedReader()
                        while (reader.readLine()?.isNotEmpty() == true) Unit
                        it.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".toByteArray())
                        it.getOutputStream().flush()
                    }
                }
            }
        }

        override fun close() = server.close()
    }

    private class Counts : EventListener() {
        val connects = AtomicInteger()
        override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) { connects.incrementAndGet() }
    }

    private class CountingDns : Dns {
        val lookups = AtomicInteger()
        override fun lookup(hostname: String): List<InetAddress> {
            lookups.incrementAndGet()
            return listOf(InetAddress.getByName("127.0.0.1"))
        }
    }

    private fun client(chosen: () -> Boolean, dns: Dns, counts: Counts) = OkHttpClient.Builder()
        .addInterceptor(RouteChoiceGate(chosen))
        .dns(dns)
        .eventListener(counts)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    @Test
    fun nothingGoesOutBeforeTheChoice() {
        CountingServer().use { server ->
            val dns = CountingDns()
            val counts = Counts()
            val client = client({ false }, dns, counts)
            val base = "http://booru.example.invalid:${server.port}"
            // What a fresh vault would ask for first: the grid, a suggestion, a thumbnail, a file.
            val asked = listOf("/posts.json?limit=20", "/autocomplete.json?search[query]=bl", "/180x180/a.jpg", "/original/a.jpg")
            asked.forEach { path ->
                try {
                    client.newCall(Request.Builder().url(base + path).build()).execute().close()
                    fail("$path went out before the choice")
                } catch (e: RouteNotChosenException) {
                    // expected
                }
            }
            assertEquals("connections the server received", 0, server.accepted.get())
            assertEquals("names looked up", 0, dns.lookups.get())
            assertEquals("connections attempted", 0, counts.connects.get())
        }
    }

    @Test
    fun theChoiceOpensTheWay() {
        CountingServer().use { server ->
            var chosen = false
            val client = client({ chosen }, CountingDns(), Counts())
            val request = Request.Builder().url("http://booru.example.invalid:${server.port}/posts.json").build()
            runCatching { client.newCall(request).execute().close() }
            chosen = true
            client.newCall(request).execute().use { assertEquals("ok", it.body.string()) }
            assertEquals(1, server.accepted.get())
        }
    }

    @Test
    fun aFreshInstallIsAskedAndAnOlderVaultIsNot() {
        assertFalse("a fresh install is asked", TorManager.routeChosenAtStart(stored = null, hasVault = false))
        assertTrue("an install that already has a vault is not", TorManager.routeChosenAtStart(stored = null, hasVault = true))
        assertFalse("a vault closed before the answer is still asked", TorManager.routeChosenAtStart(stored = false, hasVault = true))
        assertTrue("an answer given is kept", TorManager.routeChosenAtStart(stored = true, hasVault = false))
    }
}
