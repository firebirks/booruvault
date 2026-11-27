package io.github.firebirks.booruvault.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The engine of the built-in browsers: what it starts with, how it takes a route, what its extension answers. */
class GeckoPrefsTest {
    init {
        check(TestPublicSuffixes.installed)
    }

    @Test
    fun theEngineStartsWithWebRtcOffAndAProxyThatLeadsNowhere() {
        val prefs = GeckoPrefs.initial
        assertEquals(false, prefs["media.peerconnection.enabled"])
        assertEquals("a proxy is on from the first moment", 1, prefs["network.proxy.type"])
        assertEquals("127.0.0.1", prefs["network.proxy.socks"])
        assertEquals("on a port nothing listens on", GeckoPrefs.NO_PORT, prefs["network.proxy.socks_port"])
        assertEquals("names are resolved by the proxy", true, prefs["network.proxy.socks5_remote_dns"])
        assertEquals(5, prefs["network.proxy.socks_version"])
        assertEquals("the phone's own addresses go to it too", true, prefs["network.proxy.allow_hijacking_localhost"])
        assertEquals("", prefs["network.proxy.no_proxies_on"])
        assertEquals("and there is no direct way to fall back on", false, prefs["network.proxy.failover_direct"])
        assertEquals("no QUIC: it is UDP", false, prefs["network.http.http3.enable"])
        assertEquals("no name resolved by the engine itself", true, prefs["network.dns.disabled"])
        assertEquals(false, prefs["network.proxy.allow_bypass"])
        assertEquals("were WebRTC back on, nothing of it around the proxy", true, prefs["media.peerconnection.ice.proxy_only_if_behind_proxy"])
    }

    @Test
    fun theEngineMakesNoConnectionOfItsOwn() {
        val prefs = GeckoPrefs.base
        for (name in listOf(
            "network.captive-portal-service.enabled", "network.connectivity-service.enabled", "browser.safebrowsing.malware.enabled",
            "browser.safebrowsing.phishing.enabled", "browser.safebrowsing.update.enabled", "dom.push.enabled", "toolkit.telemetry.enabled",
        )) assertEquals(name, false, prefs[name])
        assertEquals("data:,", prefs["services.settings.server"])
    }

    @Test
    fun pagesReachNothingOfThePhoneOrOfOtherAppsThroughTheEngine() {
        val prefs = GeckoPrefs.base
        for (name in listOf(
            "security.webauth.webauthn", "media.webspeech.synth.enabled", "dom.screenwakelock.enabled", "media.video-wakelock",
            "media.eme.enabled", "network.webtransport.enabled", "browser.dom.window.dump.enabled",
        )) assertEquals(name, false, prefs[name])
    }

    @Test
    fun aRouteSetsThePortBeforeItTurnsTheProxyOn() {
        val tor = GeckoPrefs.route(Route.TOR, 9150)
        assertEquals(
            listOf(
                "intl.accept_languages", "privacy.fingerprintingProtection", "privacy.fingerprintingProtection.overrides",
                "network.dns.disabled", "network.proxy.socks", "network.proxy.socks_port", "network.proxy.type",
            ),
            tor.keys.toList(),
        )
        assertEquals(true, tor["network.dns.disabled"])
        assertEquals(9150, tor["network.proxy.socks_port"])
        assertEquals(1, tor["network.proxy.type"])
        val direct = GeckoPrefs.route(Route.DIRECT, 9150)
        assertEquals(
            "directly, the engine resolves names again, once the proxy is off",
            listOf("network.proxy.type", "network.dns.disabled", "privacy.fingerprintingProtection", "privacy.fingerprintingProtection.overrides"),
            direct.keys.toList(),
        )
        assertEquals(0, direct["network.proxy.type"])
        assertEquals(false, direct["network.dns.disabled"])
    }

    @Test
    fun theConfigurationFileQuotesStringsAndNothingElse() {
        val yaml = GeckoPrefs.yaml(linkedMapOf("a.b" to false, "c" to 5, "d" to "127.0.0.1", "e" to ""))
        assertEquals("prefs:\n  a.b: false\n  c: 5\n  d: \"127.0.0.1\"\n  e: \"\"\n", yaml)
    }

    @Test
    fun aQuoteOrABackslashInAStringDoesNotEndIt() {
        val prefs = linkedMapOf<String, Any>("a" to "say \"no\"", "b" to "back\\slash", "c" to "two\nlines", "d" to 3)
        val yaml = GeckoPrefs.yaml(prefs)
        assertEquals("prefs:\n  a: \"say \\\"no\\\"\"\n  b: \"back\\\\slash\"\n  c: \"two\\nlines\"\n  d: 3\n", yaml)
        // As GeckoView reads the file: with SnakeYAML, into the same values.
        val read = org.yaml.snakeyaml.Yaml().load<Map<String, Map<String, Any>>>(yaml).getValue("prefs")
        assertEquals(prefs, read)
    }

    @Test
    fun theFileOfTheEngineReadsBackAsItsPreferencesAndEnvironment() {
        val file = org.yaml.snakeyaml.Yaml().load<Map<String, Map<String, Any>>>(GeckoPrefs.yaml(GeckoPrefs.initial, GeckoPrefs.env))
        assertEquals(GeckoPrefs.initial, file.getValue("prefs"))
        assertEquals(mapOf("MOZ_REMOTE_SETTINGS_DISABLED" to "1"), file.getValue("env"))
    }

    @Test
    fun theEngineFetchesNoListOfMozillas() {
        assertEquals(false, GeckoPrefs.base["extensions.blocklist.enabled"])
        assertTrue("extensions.blocklist.enabled" in GeckoPrefs.required)
        assertEquals("1", GeckoPrefs.env["MOZ_REMOTE_SETTINGS_DISABLED"])
    }

    @Test
    fun anEngineHoldingItsPreferencesIsTakenAsIs() {
        assertEquals(emptyList<String>(), GeckoPrefs.notHeld(GeckoPrefs.base))
        // An empty string may come back as nothing.
        assertEquals(emptyList<String>(), GeckoPrefs.notHeld(GeckoPrefs.base + ("network.proxy.no_proxies_on" to null)))
    }

    @Test
    fun anEngineThatStartedWithItsOwnPreferencesIsNotTaken() {
        val stock = GeckoPrefs.base + mapOf("media.peerconnection.enabled" to true, "network.proxy.failover_direct" to true)
        assertEquals(listOf("media.peerconnection.enabled", "network.proxy.failover_direct"), GeckoPrefs.notHeld(stock))
        assertEquals("a number for a number", listOf("network.trr.mode"), GeckoPrefs.notHeld(GeckoPrefs.base + ("network.trr.mode" to 0)))
    }

    @Test
    fun aPreferenceTheEngineDoesNotHaveCountsOnlyIfItIsRequired() {
        assertEquals("kept for later versions", emptyList<String>(), GeckoPrefs.notHeld(GeckoPrefs.base - "app.normandy.enabled"))
        assertEquals(listOf("media.peerconnection.enabled"), GeckoPrefs.notHeld(GeckoPrefs.base - "media.peerconnection.enabled"))
        assertEquals("an engine that answers nothing holds nothing", GeckoPrefs.required.toSet(), GeckoPrefs.notHeld(emptyMap()).toSet())
        assertTrue(GeckoPrefs.required.all { it in GeckoPrefs.base })
    }

    @Test
    fun throughTorPagesAreToldEnglishAndUtcAndDirectlyThePhonesOwnAgain() {
        val tor = GeckoPrefs.route(Route.TOR, 9150)
        assertEquals(true, tor["privacy.fingerprintingProtection"])
        assertEquals("only the time zone is added to the protection's own targets", "+JSDateTimeUTC", tor["privacy.fingerprintingProtection.overrides"])
        assertEquals(listOf("en-US"), GeckoPrefs.TOR_LOCALES.toList())
        val direct = GeckoPrefs.route(Route.DIRECT, 9150)
        assertEquals(false, direct["privacy.fingerprintingProtection"])
        assertEquals("", direct["privacy.fingerprintingProtection.overrides"])
    }

    @Test
    fun theStartUpProxyLeadsNowhereAndLeavesTheLanguageAlone() {
        val start = GeckoPrefs.startProxy
        assertEquals(1, start["network.proxy.type"])
        assertEquals(GeckoPrefs.NO_PORT, start["network.proxy.socks_port"])
        assertFalse(GeckoPrefs.LANGUAGES in start)
        assertFalse("privacy.fingerprintingProtection" in start)
    }

    @Test
    fun whatTheExtensionAnswersBecomesCookiesOfTheSiteAlone() {
        val answer = BrowserCookies.parse(
            """{"id":7,"cookies":{
                "https://safebooru.example.com/posts":[{"name":"cf_clearance","value":"pass","httpOnly":true},{"name":"session","value":"s1"}],
                "https://elsewhere.test/":[{"name":"session","value":"other"}],
                "not an address":[{"name":"x","value":"y"}]}}""",
        )
        assertEquals(7, answer.id)
        assertNull(answer.error)
        val cookies = answer.of("example.com", expires = 1_000_000L)
        assertEquals(listOf("cf_clearance", "session"), cookies.map { it.name })
        val clearance = cookies.first { it.name == "cf_clearance" }
        assertEquals("the clearance is the site's, as its image hosts check it too", "example.com", clearance.domain)
        assertTrue(!clearance.hostOnly)
        val session = cookies.first { it.name == "session" }
        assertEquals("s1", session.value)
        assertEquals("every other cookie stays on the host it was read from", "safebooru.example.com", session.domain)
        assertTrue(session.hostOnly && session.secure)
    }

    @Test
    fun aClearanceAPageScriptCouldHaveWrittenIsNotTaken() {
        val answer = BrowserCookies.parse(
            """{"id":8,"cookies":{"https://safebooru.example.com/":[{"name":"cf_clearance","value":"x","httpOnly":false},{"name":"session","value":"s1","httpOnly":true}]}}""",
        )
        val cookies = answer.of("example.com", expires = 1_000_000L)
        assertEquals(listOf("session"), cookies.map { it.name })
        assertTrue("kept out of the page's scripts as the site set it", cookies.single().httpOnly)
    }

    @Test
    fun anAnswerThatSaysWhatWentWrongIsRead() {
        assertEquals("unknown op", BrowserCookies.parse("""{"id":3,"error":"unknown op"}""").error)
    }

    @Test
    fun throughTorPagesAreAskedInEnglishAndDirectlyInThePhonesLanguagesAgain() {
        assertEquals("en-US, en", GeckoPrefs.route(Route.TOR, 9150, "it-IT,en-US")["intl.accept_languages"])
        assertEquals("from the first moment too", "en-US, en", GeckoPrefs.initial["intl.accept_languages"])
        assertEquals("it-IT,en-US", GeckoPrefs.route(Route.DIRECT, 9150, "it-IT,en-US")["intl.accept_languages"])
        assertEquals("the last of all, once the proxy is off", "intl.accept_languages", GeckoPrefs.route(Route.DIRECT, 9150, "it-IT,en-US").keys.last())
        assertFalse("not known, they are left as they are", "intl.accept_languages" in GeckoPrefs.route(Route.DIRECT, 9150))
    }
}
