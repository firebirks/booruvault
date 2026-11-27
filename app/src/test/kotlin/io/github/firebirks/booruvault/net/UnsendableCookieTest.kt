package io.github.firebirks.booruvault.net

import io.github.firebirks.booruvault.booru.withSiteHeaders
import io.github.firebirks.booruvault.vault.VaultManager
import io.github.firebirks.booruvault.vault.crypto.Kdf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A cookie the client could not send, which a page's script can set in the engine, is kept
 * nowhere and sent nowhere: the client throws on such a header, and on its own threads that ends
 * the app, at every request to the site for as long as the cookie is kept.
 */
class UnsendableCookieTest {
    init {
        check(TestPublicSuffixes.installed)
    }

    @get:Rule
    val tmp = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val board = "https://board.example.com/posts".toHttpUrl()
    private val later = System.currentTimeMillis() + 3_600_000L

    // An e with an acute accent: a byte past ASCII, as `document.cookie` may set it.
    private val accented = "caf\u00e9"

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun theClientSendsPrintableAsciiAndNothingElse() {
        assertTrue(Challenge.sendable("cf_clearance", "a.B-c_9~=/+ ,x"))
        assertFalse(Challenge.sendable("cf_clearance", accented))
        assertFalse(Challenge.sendable("session", "a;b=c"))
        assertFalse(Challenge.sendable("session", "line\nbreak"))
        assertFalse(Challenge.sendable("na me", "v"))
        assertFalse(Challenge.sendable("", "v"))
    }

    @Test
    fun aBrowserCookieTheClientCouldNotSendIsNotTaken() {
        assertNull(Challenge.browserCookie(Challenge.CLEARANCE_COOKIE, accented, board, "example.com", later, httpOnly = true))
        assertNotNull(Challenge.browserCookie(Challenge.CLEARANCE_COOKIE, "pass", board, "example.com", later, httpOnly = true))
        val answer = BrowserCookies.parse(
            """{"id":1,"cookies":{"https://board.example.com/":[{"name":"session","value":"caf\u00e9"},{"name":"ok","value":"1"}]}}""",
        )
        assertEquals(listOf("ok"), answer.of("example.com", later).map { it.name })
    }

    @Test
    fun aRequestLeavesOutACookieItCouldNotCarryRatherThanThrow() {
        val bad = Cookie.Builder().name("session").value(accented).hostOnlyDomain(board.host).build()
        val good = Cookie.Builder().name("ok").value("1").hostOnlyDomain(board.host).build()
        val sent = withSiteHeaders(Request.Builder().url(board).build(), passedAgent = null, cleared = false, cookies = listOf(bad, good))
        assertEquals("ok=1", sent.header("Cookie"))
    }

    @Test
    fun theJarKeepsNoCookieTheClientCouldNotSend() = runBlocking {
        val root = tmp.newFolder()
        val vault = VaultManager(root, json)
        vault.create("test password".toCharArray(), Kdf.newParams(memoryKiB = 1024, iterations = 1))
        val jar = SecureCookieJar(root, vault, json, scope)
        val good = Cookie.Builder().name("ok").value("1").path("/").expiresAt(later).hostOnlyDomain(board.host).build()
        val bad = Cookie.Builder().name("session").value(accented).path("/").expiresAt(later).hostOnlyDomain(board.host).build()
        // The jar reads the vault on a thread of its own once it is open; until then it keeps nothing.
        withTimeout(5_000) {
            while (jar.cookiesFor(Route.DIRECT, board).isEmpty()) {
                jar.recordClearance(Route.DIRECT, board.host, "gecko", listOf(good, bad))
                delay(20)
            }
        }
        assertEquals(listOf("ok"), jar.cookiesFor(Route.DIRECT, board).map { it.name })
        jar.renewFromBrowser(Route.DIRECT, board.host, "gecko", listOf(bad), settled = true)
        assertEquals(listOf("ok"), jar.keptCookies(Route.DIRECT, board).map { it.name })
    }
}
