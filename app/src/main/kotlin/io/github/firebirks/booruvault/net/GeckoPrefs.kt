package io.github.firebirks.booruvault.net

/**
 * The preferences of GeckoView, the engine of the built-in browsers. Android's WebView has no
 * setting that turns WebRTC off or sends it through the proxy, and a page script cannot keep it
 * out of every frame; Gecko leaves it out of the engine by a preference.
 */
object GeckoPrefs {
    /**
     * What holds whatever the route. WebRTC is not there at all, in any frame. The proxy, once
     * set, takes every request, the phone's own addresses included, resolves names itself, and has
     * no direct way to fall back on. And Gecko makes no connection of its own: with its own
     * preferences it asks Mozilla for remote settings, looks for a captive portal and fetches
     * Google's Safe Browsing lists. Most of these are the ones Tor Browser sets for the same ends
     * (its 000-tor-browser.js and 001-base-profile.js), which a stock GeckoView does not have.
     */
    val base: Map<String, Any> = linkedMapOf(
        "media.peerconnection.enabled" to false,
        "media.navigator.enabled" to false,
        // Should WebRTC ever come back on: no address of the phone's, and nothing around the proxy.
        "media.peerconnection.ice.obfuscate_host_addresses" to true,
        "media.peerconnection.ice.relay_only" to true,
        "media.peerconnection.ice.default_address_only" to true,
        "media.peerconnection.ice.no_host" to true,
        "media.peerconnection.ice.proxy_only_if_behind_proxy" to true,
        "network.proxy.socks_version" to 5,
        // SOCKS5's switch, and SOCKS4's should the version ever change.
        "network.proxy.socks5_remote_dns" to true,
        "network.proxy.socks_remote_dns" to true,
        "network.proxy.allow_hijacking_localhost" to true,
        "network.proxy.no_proxies_on" to "",
        // The ways around a proxy the engine keeps for its own requests.
        "network.proxy.failover_direct" to false,
        "network.proxy.allow_bypass" to false,
        "network.proxy.fast_path_system_direct" to false,
        // No resolver of the engine's own, and no QUIC, which is UDP and takes no SOCKS proxy.
        "network.trr.mode" to 5,
        "network.http.http3.enable" to false,
        "network.webtransport.enabled" to false,
        // Nothing asked for before the page asks.
        "network.dns.disablePrefetch" to true,
        "network.dns.disablePrefetchFromHTTPS" to true,
        "dom.prefetch_dns_for_anchor_http_document" to false,
        "dom.prefetch_dns_for_anchor_https_document" to false,
        "network.prefetch-next" to false,
        "network.http.speculative-parallel-limit" to 0,
        // What a page could reach of the phone, or of other apps, through the engine. WebAuthn
        // calls Google Play services' FIDO library, which the app leaves out: a page asking whether
        // a passkey is there would take the app down. Speech is spoken by the phone's speech app,
        // which may send the text out itself, outside Tor. A wake lock keeps the screen, and so
        // the phone and the vault, from locking. DRM asks for its keys around the proxy.
        "security.webauth.webauthn" to false,
        "media.webspeech.synth.enabled" to false,
        "dom.screenwakelock.enabled" to false,
        "media.video-wakelock" to false,
        "media.eme.enabled" to false,
        "media.mediadrm-widevinecdm.visible" to false,
        // A page's dump() would reach the system log.
        "browser.dom.window.dump.enabled" to false,
        // Pages do not reach the phone's own network.
        "network.lna.enabled" to true,
        "network.lna.blocking" to true,
        "network.socket.ip_addr_any.disabled" to true,
        "geo.enabled" to false,
        "geo.provider.network.url" to "",
        // The engine's own connections.
        "network.captive-portal-service.enabled" to false,
        "network.connectivity-service.enabled" to false,
        "captivedetect.canonicalURL" to "",
        "browser.safebrowsing.malware.enabled" to false,
        "browser.safebrowsing.phishing.enabled" to false,
        "browser.safebrowsing.downloads.enabled" to false,
        "browser.safebrowsing.downloads.remote.enabled" to false,
        "browser.safebrowsing.blockedURIs.enabled" to false,
        "browser.safebrowsing.update.enabled" to false,
        // Ignored on Firefox's release channel, which this engine is on: remote settings keep to
        // Mozilla's server whatever this says. [env] turns their syncs off, and the add-on
        // blocklist, which fetches its filters from there as attachments, is off.
        "services.settings.server" to "data:,",
        "extensions.blocklist.enabled" to false,
        "security.remote_settings.crlite_filters.enabled" to false,
        "security.remote_settings.intermediates.enabled" to false,
        "browser.region.network.url" to "",
        "browser.region.update.enabled" to false,
        "dom.push.enabled" to false,
        "dom.push.serverURL" to "",
        "toolkit.telemetry.enabled" to false,
        "toolkit.telemetry.unified" to false,
        "toolkit.telemetry.server" to "data:,",
        "datareporting.healthreport.uploadEnabled" to false,
        "datareporting.policy.dataSubmissionEnabled" to false,
        "extensions.update.enabled" to false,
        "extensions.systemAddon.update.enabled" to false,
        "extensions.getAddons.cache.enabled" to false,
        // Not in GeckoView 161, which has no predictor, plug-in installer, Normandy or updater:
        // kept should a later one bring them back.
        "network.predictor.enabled" to false,
        "media.gmp-provider.enabled" to false,
        "media.gmp-manager.updateEnabled" to false,
        "media.gmp-manager.url.override" to "data:text/plain,",
        "app.normandy.enabled" to false,
        "app.normandy.api_url" to "",
        "app.update.enabled" to false,
    )

    /** A SOCKS port nothing listens on: what the engine starts with, so nothing leaves before a route is applied. */
    const val NO_PORT = 1

    /** The languages the engine asks pages in, and tells their scripts. */
    const val LANGUAGES = "intl.accept_languages"

    /**
     * What it asks in through Tor: English, as Tor Browser does for everyone. The phone's
     * languages, which the engine asks in by itself, would tell a site something the exit hides,
     * and set one user apart from the others who leave by it.
     */
    const val TOR_LANGUAGES = "en-US, en"

    /**
     * The engine's own locale through Tor, which pages' scripts read through `Intl`, dates and
     * `navigator.language`: the header alone leaves them in the phone's. Set on the runtime,
     * which also asks in the phone's language after it, so [TOR_LANGUAGES] is set again after.
     */
    val TOR_LOCALES = arrayOf("en-US")

    /** The fingerprinting protection's own switch, and the targets added to its defaults. */
    private const val PROTECTION = "privacy.fingerprintingProtection"
    private const val PROTECTION_TARGETS = "privacy.fingerprintingProtection.overrides"

    /**
     * What pages' scripts are told through Tor besides the language: the time zone as UTC, as
     * Tor Browser tells them, rather than the phone's, which names where it is.
     */
    val torPage: Map<String, Any> = linkedMapOf(LANGUAGES to TOR_LANGUAGES, PROTECTION to true, PROTECTION_TARGETS to "+JSDateTimeUTC")

    /**
     * The proxy for [route]: Orbot's port for everything, or none. In the order they are to be
     * set. Directly, the engine asks pages in [phoneLanguages] again, when they are given.
     */
    fun route(route: Route, socksPort: Int, phoneLanguages: String? = null): Map<String, Any> = when (route) {
        // The language first: no request through the proxy asks in the phone's. Then the address,
        // and the switch last: no request finds the proxy on with the old port. Through the proxy
        // the engine resolves no name at all, as Tor Browser has it: a lookup of its own would be
        // a way around.
        Route.TOR -> linkedMapOf<String, Any>().apply { putAll(torPage) } + linkedMapOf(
            "network.dns.disabled" to true,
            "network.proxy.socks" to "127.0.0.1",
            "network.proxy.socks_port" to socksPort,
            "network.proxy.type" to MANUAL,
        )
        Route.DIRECT -> linkedMapOf<String, Any>("network.proxy.type" to NONE, "network.dns.disabled" to false, PROTECTION to false, PROTECTION_TARGETS to "")
            .apply { if (!phoneLanguages.isNullOrBlank()) put(LANGUAGES, phoneLanguages) }
    }

    /** The engine's first preferences: [base], and a proxy that leads nowhere. */
    val initial: Map<String, Any> get() = base + route(Route.TOR, NO_PORT)

    /**
     * The proxy that leads nowhere, without the language. The engine keeps the last route's
     * preferences from one start to the next, over the ones its file starts it with: these are
     * put back before a new runtime takes its first route.
     */
    val startProxy: Map<String, Any> get() = route(Route.TOR, NO_PORT) - torPage.keys

    /**
     * The preferences of [base] without which the engine must not load a page: WebRTC, the proxy
     * and its ways around, its own resolver and connections, WebAuthn. GeckoView 161 has each of
     * them, so one it does not answer for is an engine that does not answer as it should.
     */
    val required: Set<String> = setOf(
        "media.peerconnection.enabled",
        "media.navigator.enabled",
        "network.proxy.socks_version",
        "network.proxy.socks5_remote_dns",
        "network.proxy.allow_hijacking_localhost",
        "network.proxy.no_proxies_on",
        "network.proxy.failover_direct",
        "network.proxy.allow_bypass",
        "network.trr.mode",
        "network.http.http3.enable",
        "network.captive-portal-service.enabled",
        "network.connectivity-service.enabled",
        "extensions.blocklist.enabled",
        "security.webauth.webauthn",
    )

    /**
     * The names of [base] the engine does not hold, from what it answers: [found], name to value,
     * for the names it has. GeckoView takes its first preferences from a file and goes on without
     * them, saying nothing, should it fail to read it. A name it does not have is not there to
     * hold anything, unless it is [required]: [base] keeps some for later versions. A string set
     * empty may come back as nothing.
     */
    fun notHeld(found: Map<String, Any?>): List<String> = base.filter { (name, value) ->
        if (name !in found) name in required else value != found[name] && !(value == "" && found[name] == null)
    }.keys.toList()

    /**
     * The engine's environment, which GeckoView sets from the same file. Remote settings, which
     * would sync Mozilla's lists from Mozilla's server whatever the preferences say, skip every
     * sync with it.
     */
    val env: Map<String, String> = linkedMapOf("MOZ_REMOTE_SETTINGS_DISABLED" to "1")

    /** [prefs] and [env] as the configuration file GeckoView reads at start. */
    fun yaml(prefs: Map<String, Any>, env: Map<String, String> = emptyMap()): String =
        "prefs:\n" + prefs.entries.joinToString("") { (name, value) -> "  $name: ${if (value is String) quoted(value) else value}\n" } +
            if (env.isEmpty()) "" else "env:\n" + env.entries.joinToString("") { (name, value) -> "  $name: ${quoted(value)}\n" }

    /** [text] as a YAML string, which a backslash or a quote would otherwise end or change. */
    private fun quoted(text: String): String =
        "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    private const val NONE = 0
    private const val MANUAL = 1
}
