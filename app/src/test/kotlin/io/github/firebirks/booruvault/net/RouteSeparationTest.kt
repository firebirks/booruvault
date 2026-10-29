package io.github.firebirks.booruvault.net

import io.github.firebirks.booruvault.booru.DefaultUserAgent
import io.github.firebirks.booruvault.booru.SiteHeaders
import io.github.firebirks.booruvault.booru.UserAgents
import io.github.firebirks.booruvault.vault.SealedJson
import io.github.firebirks.booruvault.vault.VaultManager
import io.github.firebirks.booruvault.vault.VaultState
import io.github.firebirks.booruvault.vault.crypto.Kdf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.DataInputStream
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Nothing a site hands the app along one route is shown along the other. The same address is
 * reached two ways: directly, where a local server plays the site as seen from home, and through
 * a fake SOCKS5 proxy that plays the site as seen from a Tor exit. Each records the cookies and
 * the agent it is shown and sets a session cookie of its own.
 */
class RouteSeparationTest {
    init {
        check(TestPublicSuffixes.installed)
    }

    @get:Rule
    val tmp = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() = scope.cancel()

    private class Seen(val cookie: String?, val agent: String?)

    /** Answers HTTP on [socket], records what it was shown and sets `session=[session]`. */
    private fun serve(socket: Socket, session: String, seen: MutableList<Seen>) {
        val reader = socket.getInputStream().bufferedReader()
        var cookie: String? = null
        var agent: String? = null
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            val name = line.substringBefore(':').trim()
            if (name.equals("Cookie", ignoreCase = true)) cookie = line.substringAfter(':').trim()
            if (name.equals("User-Agent", ignoreCase = true)) agent = line.substringAfter(':').trim()
        }
        seen += Seen(cookie, agent)
        val answer = "HTTP/1.1 200 OK\r\nSet-Cookie: session=$session; Path=/\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok"
        socket.getOutputStream().write(answer.toByteArray())
        socket.getOutputStream().flush()
    }

    private fun home(seen: MutableList<Seen>): ServerSocket {
        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                socket.use { serve(it, "home", seen) }
            }
        }
        return server
    }

    /** A SOCKS5 proxy that grants any CONNECT and then answers as the site itself. */
    private fun torExit(seen: MutableList<Seen>): ServerSocket {
        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                socket.use {
                    val input = DataInputStream(it.getInputStream())
                    val output = it.getOutputStream()
                    check(input.readUnsignedByte() == 5)
                    repeat(input.readUnsignedByte()) { input.readUnsignedByte() }
                    output.write(byteArrayOf(5, 0))
                    repeat(3) { input.readUnsignedByte() }
                    when (input.readUnsignedByte()) {
                        1 -> ByteArray(4).also(input::readFully)
                        3 -> ByteArray(input.readUnsignedByte()).also(input::readFully)
                        4 -> ByteArray(16).also(input::readFully)
                    }
                    input.readUnsignedShort()
                    output.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0))
                    serve(it, "tor", seen)
                }
            }
        }
        return server
    }

    private suspend fun openJar(root: File = tmp.newFolder(), route: () -> Route): SecureCookieJar {
        val vault = VaultManager(root, json)
        vault.create("test password".toCharArray(), Kdf.newParams(memoryKiB = 1024, iterations = 1))
        return SecureCookieJar(root, vault, json, scope, route).also { it.awaitLoaded() }
    }

    /** The jar reads the vault on a thread of its own once it is open; until then it keeps nothing. */
    private suspend fun SecureCookieJar.awaitLoaded() = withTimeout(5_000) {
        while (userAgentFor(Route.DIRECT, "ready.invalid") == null) {
            recordClearance(Route.DIRECT, "ready.invalid", "ready", emptyList())
            delay(20)
        }
    }

    private fun client(jar: SecureCookieJar, tor: () -> Boolean, socksPort: Int, before: () -> Unit = {}) = OkHttpClient.Builder()
        .proxySelector(TorProxySelector(tor, { socksPort }))
        .dns(TorAwareDns(tor))
        .addInterceptor { chain ->
            before()
            chain.proceed(chain.request())
        }
        .addInterceptor(DefaultUserAgent)
        .addNetworkInterceptor(SiteHeaders(jar))
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private fun OkHttpClient.get(url: String, agent: String? = null) =
        newCall(Request.Builder().url(url).apply { agent?.let { header("User-Agent", it) } }.build()).execute().use { it.body.string() }

    @Test
    fun aSessionSetAlongOneRouteNeverTravelsTheOther() = runBlocking {
        val atHome = Collections.synchronizedList(mutableListOf<Seen>())
        val atExit = Collections.synchronizedList(mutableListOf<Seen>())
        home(atHome).use { site ->
            torExit(atExit).use { socks ->
                var tor = true
                val jar = openJar { Route.of(tor) }
                val client = client(jar, { tor }, socks.localPort)
                val url = "http://127.0.0.1:${site.localPort}/posts.json"

                client.get(url)
                tor = false
                client.get(url)
                tor = true
                client.get(url)
                tor = false
                client.get(url)

                assertEquals(listOf(null, "session=tor"), atExit.map { it.cookie })
                assertEquals("the Tor session is not shown from home", listOf(null, "session=home"), atHome.map { it.cookie })
            }
        }
    }

    @Test
    fun aPassEarnedAlongTorIsNotShownFromHome() = runBlocking {
        val atHome = Collections.synchronizedList(mutableListOf<Seen>())
        val atExit = Collections.synchronizedList(mutableListOf<Seen>())
        home(atHome).use { site ->
            torExit(atExit).use { socks ->
                var tor = false
                val jar = openJar { Route.of(tor) }
                val url = "http://127.0.0.1:${site.localPort}/original/a.jpg"
                val clearance = Cookie.Builder().name(Challenge.CLEARANCE_COOKIE).value("earned-over-tor").hostOnlyDomain("127.0.0.1").path("/").build()
                jar.recordClearance(Route.TOR, "127.0.0.1", "TorBrowser/1", listOf(clearance))
                val client = client(jar, { tor }, socks.localPort)

                client.get(url, UserAgents.APP)
                tor = true
                client.get(url, UserAgents.APP)

                assertNull("no clearance from home", atHome.single().cookie)
                assertEquals("and no browser name: the app's own", UserAgents.APP, atHome.single().agent)
                assertEquals("${Challenge.CLEARANCE_COOKIE}=earned-over-tor", atExit.single().cookie)
                assertEquals("TorBrowser/1", atExit.single().agent)
            }
        }
    }

    @Test
    fun aSwitchFlippedWhileTheRequestIsOnItsWayHandsItNothingOfTheOtherRoute() = runBlocking {
        val atHome = Collections.synchronizedList(mutableListOf<Seen>())
        val atExit = Collections.synchronizedList(mutableListOf<Seen>())
        home(atHome).use { site ->
            torExit(atExit).use { socks ->
                var tor = true
                val jar = openJar { Route.of(tor) }
                val url = "http://127.0.0.1:${site.localPort}/posts.json"
                client(jar, { tor }, socks.localPort).get(url)
                // The request starts along Tor; the switch is flipped before its connection is made.
                client(jar, { tor }, socks.localPort, before = { tor = false }).get(url)

                assertEquals(1, atExit.size)
                assertNull("it left directly, with nothing the Tor exit was given", atHome.single().cookie)
            }
        }
    }

    @Test
    fun whatOneJarForBothRoutesStoredGoesToTheRouteInUse() = runBlocking {
        val root = tmp.newFolder()
        val vault = VaultManager(root, json)
        vault.create("test password".toCharArray(), Kdf.newParams(memoryKiB = 1024, iterations = 1))
        val stored = SealedJson(File(root, "cookies.bin"), JsonElement.serializer(), json)
        val expires = System.currentTimeMillis() + 86_400_000L
        stored.write(
            vault.recipient!!,
            json.parseToJsonElement(
                """{"cookies":[{"name":"cf_clearance","value":"old","expiresAt":$expires,"domain":"donmai.us","path":"/",""" +
                    """"secure":true,"httpOnly":false,"hostOnly":false}],"userAgents":{"donmai.us":"OldAgent"}}""",
            ),
        )
        val jar = SecureCookieJar(root, vault, json, scope) { Route.TOR }
        val url = "https://cdn.donmai.us/original/a.jpg".toHttpUrl()
        withTimeout(5_000) { while (jar.cookiesFor(Route.TOR, url).isEmpty()) delay(20) }

        assertEquals("OldAgent", jar.userAgentFor(Route.TOR, "cdn.donmai.us"))
        assertTrue(jar.cookiesFor(Route.DIRECT, url).isEmpty())
        assertNull(jar.userAgentFor(Route.DIRECT, "cdn.donmai.us"))
        // Stored again at once in the split form.
        val keys = (vault.state.value as VaultState.Unlocked).keys
        withTimeout(5_000) { while (stored.read(keys)?.jsonObject?.get("tor") == null) delay(20) }
        val split = stored.read(keys)!!.jsonObject
        assertTrue("nothing left in the old form", split["cookies"]?.jsonArray.orEmpty().isEmpty())
        assertEquals(1, split.getValue("tor").jsonObject.getValue("cookies").jsonArray.size)
    }

    @Test
    fun eachRouteHasATlsSessionCacheOfItsOwn() {
        val tls = RouteTls()
        val direct = tls.socketFactory.createSocket()
        val alsoDirect = tls.socketFactory.createSocket()
        // How OkHttp makes the socket of a connection through a SOCKS proxy.
        val throughTor = Socket(Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved("127.0.0.1", 9050)))
        assertSame(tls.factoryFor(direct), tls.factoryFor(alsoDirect))
        assertNotSame(tls.factoryFor(direct), tls.factoryFor(throughTor))
        listOf(direct, alsoDirect, throughTor).forEach { it.close() }
    }
}
