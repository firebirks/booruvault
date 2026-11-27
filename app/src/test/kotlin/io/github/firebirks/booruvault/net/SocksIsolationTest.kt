package io.github.firebirks.booruvault.net

import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * What the app's client tells Orbot about itself. Tor takes username/password authentication
 * whenever it is offered, then keeps streams with different credentials on different circuits
 * (IsolateSOCKSAuth, on by default). The built-in browsers' engine offers none unless told to,
 * and Java's client sends the system user's name unless told otherwise: the browser and the app
 * would leave Tor through different circuits, usually from different exit addresses, and a site
 * that grants a pass to one address may refuse it from another. Both are given
 * [SocksCredential]; here, the client's half. A fake SOCKS5 server answers as Tor does and
 * records what the client sends.
 */
class SocksIsolationTest {
    private class Greeting(val methods: List<Int>, val username: String, val password: String)

    private fun torLikeSocks(onGreeting: (Greeting) -> Unit): ServerSocket {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            server.accept().use { socket ->
                val input = DataInputStream(socket.getInputStream())
                val output = socket.getOutputStream()
                check(input.readUnsignedByte() == 5)
                val methods = List(input.readUnsignedByte()) { input.readUnsignedByte() }
                // Tor picks username/password whenever it is offered, unless told PreferSOCKSNoAuth.
                val userPass = USER_PASS in methods
                output.write(byteArrayOf(5, if (userPass) USER_PASS.toByte() else 0))
                var username = ""
                var password = ""
                if (userPass) {
                    check(input.readUnsignedByte() == 1)
                    username = String(ByteArray(input.readUnsignedByte()).also(input::readFully))
                    password = String(ByteArray(input.readUnsignedByte()).also(input::readFully))
                    output.write(byteArrayOf(1, 0))
                }
                onGreeting(Greeting(methods, username, password))
                // CONNECT with a domain name, answered as granted.
                repeat(3) { input.readUnsignedByte() }
                check(input.readUnsignedByte() == 3)
                ByteArray(input.readUnsignedByte()).also(input::readFully)
                input.readUnsignedShort()
                output.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0))
                val reader = socket.getInputStream().bufferedReader()
                while (reader.readLine()?.isNotEmpty() == true) Unit
                output.write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".toByteArray())
                output.flush()
            }
        }
        return server
    }

    @Test
    fun theAppsClientTellsTorTheCredentialTheEngineIsGiven() {
        SocksCredential.install()
        var greeting: Greeting? = null
        val server = torLikeSocks { greeting = it }
        server.use {
            val client = OkHttpClient.Builder()
                .proxy(orbotProxy(server.localPort))
                .dns(TorAwareDns { true })
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build()
            client.newCall(Request.Builder().url("http://cdn.example.invalid/original/a.jpg").build()).execute()
                .use { assertEquals("ok", it.body.string()) }
        }
        val sent = greeting!!
        assertTrue("the client offers username/password, so Tor isolates it by those credentials", USER_PASS in sent.methods)
        assertEquals("the one the engine's extension is told to send", SocksCredential.USER, sent.username)
        assertEquals(SocksCredential.PASSWORD, sent.password)
    }

    private companion object {
        const val USER_PASS = 2
    }
}
