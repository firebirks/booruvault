package io.github.firebirks.booruvault.net

import io.github.firebirks.booruvault.util.Problem
import io.github.firebirks.booruvault.util.toProblem
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * With Tor off an onion address leads nowhere, and the network's resolver must not be asked for
 * one: it would learn which hidden service the phone was after. And a board served over plain
 * http gets back the cookies the built-in browser brought from it.
 */
class OnionAndHttpTest {
    init {
        check(TestPublicSuffixes.installed)
    }

    private fun refusal(enabled: Boolean, host: String): Exception {
        try {
            TorAwareDns { enabled }.lookup(host)
        } catch (e: Exception) {
            return e
        }
        fail("$host was looked up")
        error("unreachable")
    }

    @Test
    fun `an onion name is never looked up, Tor on or off`() {
        val off = refusal(enabled = false, "duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad.onion")
        assertTrue(off is OnionWithoutTorException)
        assertEquals(Problem.Code.ONION_NEEDS_TOR, off.toProblem().code)
        assertTrue(refusal(enabled = false, "Example.ONION.") is OnionWithoutTorException)
        assertTrue(refusal(enabled = true, "example.onion") is TorDnsBlockedException)
    }

    @Test
    fun `only names that end in onion are onion addresses`() {
        assertTrue(isOnion("abc.onion"))
        assertTrue(isOnion("ABC.Onion."))
        assertFalse(isOnion("onion.example.com"))
        assertFalse(isOnion("example.onionsite"))
        // An address needs no lookup at all.
        assertEquals("127.0.0.1", TorAwareDns { false }.lookup("127.0.0.1").single().hostAddress)
    }

    @Test
    fun `a cookie from a page served over http goes back to it`() {
        val url = "http://booru.example.org/index.php".toHttpUrl()
        val cookie = Challenge.browserCookie("session", "login", url, "example.org", System.currentTimeMillis() + 60_000)!!
        assertFalse(cookie.secure)
        assertTrue(cookie.matches(url))
        assertTrue(cookie.hostOnly)
        assertFalse("bound to its host", cookie.matches("http://img.example.org/a.jpg".toHttpUrl()))
    }

    @Test
    fun `a cookie from a page served over https stays on https`() {
        val url = "https://booru.example.org/index.php".toHttpUrl()
        val cookie = Challenge.browserCookie("session", "login", url, "example.org", System.currentTimeMillis() + 60_000)!!
        assertTrue(cookie.secure)
        assertFalse(cookie.matches("http://booru.example.org/index.php".toHttpUrl()))
        val clearance = Challenge.browserCookie(Challenge.CLEARANCE_COOKIE, "pass", url, "example.org", System.currentTimeMillis() + 60_000)!!
        assertTrue("the clearance is the whole site's", clearance.matches("https://img.example.org/a.jpg".toHttpUrl()))
    }
}
