package io.github.firebirks.booruvault.booru

import io.github.firebirks.booruvault.download.downloadRequest
import io.github.firebirks.booruvault.net.Challenge
import io.github.firebirks.booruvault.net.Route
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * A clearance the site no longer takes is forgotten with the agent that earned it, so a Danbooru
 * board is asked under the app's name again instead of with a dead pass for ever. A local server
 * plays Cloudflare: it puts a check in front of whatever claims to be a browser.
 */
class SiteHeadersTest {
    private val safebooru = BooruSource("safebooru-donmai", "Safebooru", BooruEngine.DANBOORU, "https://safebooru.donmai.us")

    /** The direct route's passes; a request along any other route would find none. */
    private class Passes(var agent: String?, var clearance: String?) : SitePasses {
        val dropped = mutableListOf<String>()
        val worked = mutableListOf<String>()
        override fun cookiesFor(route: Route, url: HttpUrl): List<Cookie> =
            listOfNotNull(clearance?.takeIf { route == Route.DIRECT }?.let { Cookie.Builder().name(Challenge.CLEARANCE_COOKIE).value(it).hostOnlyDomain(url.host).build() })
        override fun saveFromResponse(route: Route, url: HttpUrl, cookies: List<Cookie>) = Unit
        override fun userAgentFor(route: Route, host: String) = agent?.takeIf { route == Route.DIRECT }
        override fun clearanceWorked(route: Route, host: String, value: String) {
            worked += value
        }
        override fun dropClearance(route: Route, host: String, value: String) {
            dropped += value
            if (clearance == value) {
                clearance = null
                agent = null
            }
        }
    }

    /** Answers a browser's name with a check, anything else with the file; records each agent. */
    private fun cloudflare(agents: MutableList<String>): ServerSocket {
        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                socket.use {
                    val reader = it.getInputStream().bufferedReader()
                    var agent = ""
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        if (line.startsWith("User-Agent:", ignoreCase = true)) agent = line.substringAfter(':').trim()
                    }
                    agents += agent
                    val answer = if (agent.startsWith("Mozilla/")) {
                        "HTTP/1.1 403 Forbidden\r\ncf-mitigated: challenge\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                    } else {
                        "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok"
                    }
                    it.getOutputStream().write(answer.toByteArray())
                    it.getOutputStream().flush()
                }
            }
        }
        return server
    }

    private fun client(passes: SitePasses) = OkHttpClient.Builder()
        .addInterceptor(DefaultUserAgent)
        .addNetworkInterceptor(SiteHeaders(passes))
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    @Test
    fun anExpiredClearanceIsDroppedAndTheBoardIsAskedUnderTheAppsNameAgain() {
        val agents = Collections.synchronizedList(mutableListOf<String>())
        cloudflare(agents).use { server ->
            val passes = Passes(agent = UserAgents.webView, clearance = "stale")
            val client = client(passes)
            val file = "http://127.0.0.1:${server.localPort}/original/a.jpg"
            client.newCall(downloadRequest(file, safebooru)).execute().use { assertEquals(403, it.code) }
            assertEquals(listOf("stale"), passes.dropped)
            assertEquals("a refused clearance has not worked", emptyList<String>(), passes.worked)
            assertNull("the agent goes with the clearance", passes.agent)
            client.newCall(downloadRequest(file, safebooru)).execute().use { assertEquals(200, it.code) }
            assertEquals(listOf(UserAgents.webView, UserAgents.APP), agents)
        }
    }

    @Test
    fun aCheckMetWithoutAClearanceDropsNothing() {
        val agents = Collections.synchronizedList(mutableListOf<String>())
        cloudflare(agents).use { server ->
            val passes = Passes(agent = null, clearance = null)
            val request = Request.Builder().url("http://127.0.0.1:${server.localPort}/posts.json").header("User-Agent", UserAgents.browser).build()
            client(passes).newCall(request).execute().use { assertEquals(403, it.code) }
            assertEquals(emptyList<String>(), passes.dropped)
        }
    }

    @Test
    fun aClearanceTheSiteTakesIsKept() {
        val agents = Collections.synchronizedList(mutableListOf<String>())
        cloudflare(agents).use { server ->
            // The site lets this agent through: the pass works, and nothing is forgotten.
            val passes = Passes(agent = "BooruVault-test-browser", clearance = "fresh")
            client(passes).newCall(downloadRequest("http://127.0.0.1:${server.localPort}/original/a.jpg", safebooru)).execute()
                .use { assertEquals(200, it.code) }
            assertEquals(emptyList<String>(), passes.dropped)
            assertEquals("fresh", passes.clearance)
            assertEquals("an answer brought back with it means it worked", listOf("fresh"), passes.worked)
        }
    }
}
