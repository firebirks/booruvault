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
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The jar forgets a clearance the site refused, and the agent that earned it, but nothing else:
 * not the site's other cookies, and not a clearance brought back since under another value.
 */
class ClearanceDropTest {
    init {
        check(TestPublicSuffixes.installed)
    }

    @get:Rule
    val tmp = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val file = "https://cdn.donmai.us/original/a.jpg".toHttpUrl()
    private val board = "https://safebooru.donmai.us/posts.json".toHttpUrl()

    @After
    fun tearDown() = scope.cancel()

    private fun cookie(name: String, value: String, domain: String, hostOnly: Boolean = false) = Cookie.Builder()
        .name(name).value(value).path("/").expiresAt(System.currentTimeMillis() + 86_400_000L).secure()
        .apply { if (hostOnly) hostOnlyDomain(domain) else domain(domain) }
        .build()

    private fun SecureCookieJar.clearance(url: HttpUrl) = cookiesFor(Route.DIRECT, url).firstOrNull { it.name == Challenge.CLEARANCE_COOKIE }?.value

    /** A jar on an open vault, holding a clearance for donmai.us and a login on the board. */
    private suspend fun jarWithClearance(value: String): SecureCookieJar {
        val root = tmp.newFolder()
        val vault = VaultManager(root, json)
        vault.create("test password".toCharArray(), Kdf.newParams(memoryKiB = 1024, iterations = 1))
        val jar = SecureCookieJar(root, vault, json, scope) { Route.DIRECT }
        val earned = listOf(cookie(Challenge.CLEARANCE_COOKIE, value, "donmai.us"), cookie("session", "login", "safebooru.donmai.us", hostOnly = true))
        // The jar reads the vault on a thread of its own once it is open; until then it keeps nothing.
        withTimeout(5_000) {
            while (jar.clearance(file) == null) {
                jar.recordClearance(Route.DIRECT, "cdn.donmai.us", "webview", earned)
                delay(20)
            }
        }
        return jar
    }

    @Test
    fun aRefusedClearanceGoesWithItsAgentAndTheRestStays() = runBlocking {
        val jar = jarWithClearance("stale")
        assertEquals("webview", jar.userAgentFor(Route.DIRECT, "cdn.donmai.us"))

        jar.dropClearance(Route.DIRECT, "cdn.donmai.us", "stale")

        assertNull(jar.clearance(file))
        assertNull("requests to the site go out under their own name again", jar.userAgentFor(Route.DIRECT, "safebooru.donmai.us"))
        assertEquals(listOf("session"), jar.cookiesFor(Route.DIRECT, board).map { it.name })
    }

    @Test
    fun aClearanceRefusedAfterItWorkedHasExpired() = runBlocking {
        val checked = System.currentTimeMillis()
        val jar = jarWithClearance("used")
        jar.clearanceWorked(Route.DIRECT, "cdn.donmai.us", "used")
        // Later the address changes, or the pass runs out, and the site refuses it.
        jar.dropClearance(Route.DIRECT, "cdn.donmai.us", "used")

        assertTrue("passing the check again will help", jar.passExpiredSince(Route.DIRECT, "cdn.donmai.us", checked))
        assertFalse("not for a check passed after the refusal", jar.passExpiredSince(Route.DIRECT, "cdn.donmai.us", System.currentTimeMillis() + 1))
    }

    @Test
    fun aClearanceRefusedBeforeItEverWorkedHasNotExpired() = runBlocking {
        val checked = System.currentTimeMillis()
        val jar = jarWithClearance("never")
        jar.dropClearance(Route.DIRECT, "cdn.donmai.us", "never")

        assertFalse("the site does not take the pass from the app at all", jar.passExpiredSince(Route.DIRECT, "cdn.donmai.us", checked))
    }

    @Test
    fun aClearanceBroughtBackSinceStays() = runBlocking {
        val jar = jarWithClearance("fresh")
        // A request that left with an older pass was refused after the new one came in.
        jar.dropClearance(Route.DIRECT, "cdn.donmai.us", "stale")

        assertEquals("fresh", jar.clearance(file))
        assertEquals("webview", jar.userAgentFor(Route.DIRECT, "cdn.donmai.us"))
    }
}
