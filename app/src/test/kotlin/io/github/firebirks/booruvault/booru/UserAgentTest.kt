package io.github.firebirks.booruvault.booru

import io.github.firebirks.booruvault.BuildConfig
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Danbooru boards are told the app's name: Cloudflare on cdn.donmai.us puts a check in front of
 * original files for a client that calls itself Chrome. Every other site still sees Chrome.
 */
class UserAgentTest {
    private val safebooru = BooruSource("safebooru-donmai", "Safebooru", BooruEngine.DANBOORU, "https://safebooru.donmai.us")
    private val konachan = BooruSource("konachan-net", "Konachan.net", BooruEngine.MOEBOORU, "https://konachan.net")
    private val e926 = BooruSource("e926", "e926", BooruEngine.E621, "https://e926.net")

    @Test
    fun `a danbooru board is told the app's name alone, without a version`() {
        assertEquals("BooruVault", UserAgents.forSource(safebooru))
    }

    @Test
    fun `the built-in browser stays a browser on a danbooru board`() {
        assertEquals(UserAgents.webView, UserAgents.forSource(safebooru, webView = true))
    }

    @Test
    fun `other boards still see chrome, and e926 also the app's name and version`() {
        assertEquals(UserAgents.browser, UserAgents.forSource(konachan))
        assertEquals(UserAgents.browser, UserAgents.forSource(null))
        val e926Agent = UserAgents.forSource(e926)
        assertTrue(e926Agent.startsWith(UserAgents.browser))
        assertTrue(e926Agent.endsWith("BooruVault/${BuildConfig.VERSION_NAME}"))
    }

    @Test
    fun `no name carries the account, and the built-in browser not even the app's`() {
        // The account already signs e926's API calls. In the name it would also tag every image
        // fetched without it, and from the built-in browser reach every page and script it loads.
        val signed = e926.copy(login = "someone", apiKey = "key")
        assertTrue(UserAgents.forSource(signed).endsWith("BooruVault/${BuildConfig.VERSION_NAME}"))
        assertEquals(UserAgents.webView, UserAgents.forSource(signed, webView = true))
        assertEquals(UserAgents.webView, UserAgents.forSource(e926, webView = true))
    }

    @Test
    fun `a request that names the app keeps its name while the site holds no clearance`() {
        val request = Request.Builder().url("https://cdn.donmai.us/original/a.jpg").header("User-Agent", UserAgents.forSource(safebooru)).build()
        assertEquals("BooruVault", withSiteHeaders(request, passedAgent = null, cleared = false).header("User-Agent"))
        assertEquals("BooruVault", withSiteHeaders(request, passedAgent = UserAgents.webView, cleared = false).header("User-Agent"))
    }

    @Test
    fun `a clearance is sent with the agent that earned it, even for a request that names the app`() {
        // Cloudflare binds the clearance to the browser that passed the check; under the app's
        // name it would be refused, and the download would be sent back to the check for ever.
        val request = Request.Builder().url("https://cdn.donmai.us/original/a.jpg").header("User-Agent", UserAgents.forSource(safebooru)).build()
        assertEquals(UserAgents.webView, withSiteHeaders(request, passedAgent = UserAgents.webView, cleared = true).header("User-Agent"))
    }

    @Test
    fun `a request that passes as a browser takes the recorded pass, as before`() {
        val request = Request.Builder().url("https://e926.net/posts.json").header("User-Agent", UserAgents.forSource(e926)).build()
        val passed = UserAgents.forSource(e926, webView = true)
        assertEquals(passed, withSiteHeaders(request, passedAgent = passed, cleared = true).header("User-Agent"))
        val image = Request.Builder().url("https://cdn.donmai.us/180x180/a.jpg").build()
        assertEquals(UserAgents.webView, withSiteHeaders(image, passedAgent = UserAgents.webView, cleared = false).header("User-Agent"))
    }
}
