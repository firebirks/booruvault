package io.github.firebirks.booruvault.net

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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A site renewing a cookie the user brought back from the built-in browser renews its value, and
 * nothing more: the cookie stays on the host it was bound to and keeps its deadline, whatever
 * scope and expiry the site's answer gives it.
 */
class CookieRenewalTest {
    init {
        check(TestPublicSuffixes.installed)
    }

    @get:Rule
    val tmp = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val board = "https://safebooru.donmai.us/posts.json".toHttpUrl()
    private val sibling = "https://img.safebooru.donmai.us/a.jpg".toHttpUrl()

    @After
    fun tearDown() = scope.cancel()

    /** A jar on an open vault holding a login bound to the board's host, kept until [expiresAt]. */
    private suspend fun jarWithLogin(expiresAt: Long): SecureCookieJar {
        val root = tmp.newFolder()
        val vault = VaultManager(root, json)
        vault.create("test password".toCharArray(), Kdf.newParams(memoryKiB = 1024, iterations = 1))
        val jar = SecureCookieJar(root, vault, json, scope)
        val login = Cookie.Builder().name("session").value("login").path("/").expiresAt(expiresAt).secure()
            .hostOnlyDomain(board.host).build()
        // The jar reads the vault on a thread of its own once it is open; until then it keeps nothing.
        withTimeout(5_000) {
            while (jar.cookiesFor(Route.DIRECT, board).isEmpty()) {
                jar.recordClearance(Route.DIRECT, board.host, "webview", listOf(login))
                delay(20)
            }
        }
        return jar
    }

    private fun SecureCookieJar.answer(setCookie: String) =
        saveFromResponse(Route.DIRECT, board, listOfNotNull(Cookie.parse(board, setCookie)))

    private fun SecureCookieJar.session() = cookiesFor(Route.DIRECT, board).single { it.name == "session" }

    private val inThirtyDays get() = System.currentTimeMillis() + 30L * 86_400_000L

    @Test
    fun aRenewalWithoutAnExpiryKeepsTheDeadlineOfTheCopyKept() = runBlocking {
        val deadline = inThirtyDays
        val jar = jarWithLogin(deadline)

        jar.answer("session=renewed; Path=/")

        val session = jar.session()
        assertEquals("renewed", session.value)
        assertEquals(deadline, session.expiresAt)
        assertTrue("still kept, sealed in the vault", jar.keptCookies(Route.DIRECT, board).any { it.name == "session" && it.value == "renewed" })
    }

    @Test
    fun aRenewalThatWouldOutliveTheCopyKeptIsCutToItsDeadline() = runBlocking {
        val deadline = inThirtyDays
        val jar = jarWithLogin(deadline)

        jar.answer("session=renewed; Path=/; Max-Age=315360000")

        assertEquals(deadline, jar.session().expiresAt)
    }

    @Test
    fun aRenewalThatEndsSoonerIsTaken() = runBlocking {
        val jar = jarWithLogin(inThirtyDays)
        val soon = System.currentTimeMillis() + 3_600_000L

        jar.answer("session=renewed; Path=/; Max-Age=3600")

        assertTrue(jar.session().expiresAt <= soon + 5_000)
    }

    @Test
    fun aRenewalForTheWholeDomainStaysOnTheHostTheCopyKeptWasBoundTo() = runBlocking {
        val jar = jarWithLogin(inThirtyDays)

        jar.answer("session=renewed; Path=/; Domain=safebooru.donmai.us")

        assertEquals("renewed", jar.session().value)
        assertTrue(jar.session().hostOnly)
        assertFalse("the login never reaches a sibling host", jar.cookiesFor(Route.DIRECT, sibling).any { it.name == "session" })
    }

    @Test
    fun whatASiteSetsAfterTheCopyKeptRanOutLastsForTheSession() = runBlocking {
        val jar = jarWithLogin(System.currentTimeMillis() + 1_000)
        delay(1_200)
        assertTrue(jar.cookiesFor(Route.DIRECT, board).isEmpty())

        jar.answer("session=fresh; Path=/")

        assertEquals("fresh", jar.session().value)
        assertTrue("not kept", jar.keptCookies(Route.DIRECT, board).isEmpty())
    }

    private val elsewhere = "https://other.example/".toHttpUrl()

    private fun browserCookie(name: String, value: String, url: okhttp3.HttpUrl, domain: String? = null) =
        Cookie.Builder().name(name).value(value).path("/").expiresAt(System.currentTimeMillis() + 60L * 86_400_000L).secure()
            .apply { if (domain != null) domain(domain) else hostOnlyDomain(url.host) }
            .build()

    @Test
    fun aHiddenVisitRenewsAKeptCookieWithinItsDeadline() = runBlocking {
        val deadline = inThirtyDays
        val jar = jarWithLogin(deadline)

        jar.renewFromBrowser(Route.DIRECT, board.host, "gecko", listOf(browserCookie("session", "renewed", board)), settled = true)

        assertEquals("renewed", jar.session().value)
        assertEquals(deadline, jar.session().expiresAt)
        assertEquals("the name kept with the pass stays", "webview", jar.userAgentFor(Route.DIRECT, board.host))
    }

    @Test
    fun aHiddenVisitKeepsNoCookieThatOnlySharesAKeptCookiesName() = runBlocking {
        val jar = jarWithLogin(inThirtyDays)

        jar.renewFromBrowser(
            Route.DIRECT,
            elsewhere.host,
            "gecko",
            listOf(browserCookie("session", "theirs", elsewhere), browserCookie("tracker", "1", elsewhere)),
            settled = true,
        )
        jar.renewFromBrowser(Route.DIRECT, board.host, "gecko", listOf(browserCookie("session", "wide", board, domain = "donmai.us")), settled = true)

        assertTrue(jar.keptCookies(Route.DIRECT, elsewhere).isEmpty())
        assertTrue(jar.cookiesFor(Route.DIRECT, elsewhere).isEmpty())
        assertEquals("login", jar.session().value)
        assertFalse(jar.cookiesFor(Route.DIRECT, sibling).any { it.name == "session" })
    }

    @Test
    fun aHiddenVisitKeepsTheClearanceItEarnedUnderTheNameItWasEarnedWith() = runBlocking {
        val jar = jarWithLogin(inThirtyDays)

        jar.renewFromBrowser(Route.DIRECT, board.host, "gecko", listOf(browserCookie("cf_clearance", "pass", board, domain = "donmai.us")), settled = true)

        assertTrue(jar.keptCookies(Route.DIRECT, board).any { it.name == "cf_clearance" && it.value == "pass" })
        assertEquals("gecko", jar.userAgentFor(Route.DIRECT, board.host))
    }

    @Test
    fun aHiddenVisitKeepsNoClearanceOfAnotherSite() = runBlocking {
        val jar = jarWithLogin(inThirtyDays)

        jar.renewFromBrowser(Route.DIRECT, board.host, "gecko", listOf(browserCookie("cf_clearance", "pass", elsewhere, domain = "other.example")), settled = true)

        assertTrue(jar.keptCookies(Route.DIRECT, elsewhere).isEmpty())
        assertEquals("webview", jar.userAgentFor(Route.DIRECT, board.host))
    }

    @Test
    fun aHiddenVisitThatDidNotGetPastTheCheckKeepsNoClearance() = runBlocking {
        val jar = jarWithLogin(inThirtyDays)

        jar.renewFromBrowser(Route.DIRECT, board.host, "gecko", listOf(browserCookie("cf_clearance", "half", board, domain = "donmai.us")), settled = false)

        assertFalse(jar.keptCookies(Route.DIRECT, board).any { it.name == "cf_clearance" })
        assertEquals("the name kept with the login stays", "webview", jar.userAgentFor(Route.DIRECT, board.host))
    }

    @Test
    fun theBrowsersNameGoesWithTheLastKeptCookieOfTheSite() = runBlocking {
        val jar = jarWithLogin(System.currentTimeMillis() + 1_000)
        assertEquals("webview", jar.userAgentFor(Route.DIRECT, board.host))
        delay(1_200)

        assertTrue(jar.cookiesFor(Route.DIRECT, board).isEmpty())
        assertEquals("the site's requests go under their own name again", null, jar.userAgentFor(Route.DIRECT, board.host))
    }

    @Test
    fun aVisitThatBroughtNothingBackLeavesNoName() = runBlocking {
        val jar = jarWithLogin(inThirtyDays)

        jar.recordClearance(Route.DIRECT, elsewhere.host, "gecko", emptyList())

        assertEquals(null, jar.userAgentFor(Route.DIRECT, elsewhere.host))
        assertEquals("webview", jar.userAgentFor(Route.DIRECT, board.host))
    }
}
