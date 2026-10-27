package io.github.firebirks.booruvault.net

import org.junit.Assert.assertEquals
import org.junit.Test

/** Kept cookies and clearances are scoped to the registrable domain, never to a public suffix. */
class CookieScopeTest {
    init {
        check(TestPublicSuffixes.installed)
    }

    @Test
    fun subdomainsOfOneSiteShareTheScope() {
        assertEquals("e621.net", SecureCookieJar.siteOf("static1.e621.net"))
        assertEquals("e621.net", SecureCookieJar.siteOf("e621.net"))
        assertEquals("rule34.xxx", SecureCookieJar.siteOf("api.rule34.xxx"))
        assertEquals("gelbooru.com", SecureCookieJar.siteOf("IMG4.Gelbooru.com"))
    }

    @Test
    fun multiLabelSuffixesAreNotCollapsed() {
        assertEquals("example.co.uk", SecureCookieJar.siteOf("www.example.co.uk"))
        assertEquals("alice.github.io", SecureCookieJar.siteOf("alice.github.io"))
        assertEquals("alice.github.io", SecureCookieJar.siteOf("cdn.alice.github.io"))
    }

    @Test
    fun hostsWithoutARegistrableDomainStandAlone() {
        assertEquals("github.io", SecureCookieJar.siteOf("github.io"))
        assertEquals("localhost", SecureCookieJar.siteOf("localhost"))
        assertEquals("127.0.0.1", SecureCookieJar.siteOf("127.0.0.1"))
    }
}
