package io.github.firebirks.booruvault.probe.gecko

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.GeckoPreferenceController
import io.github.firebirks.booruvault.net.GeckoPrefs
import io.github.firebirks.booruvault.net.Route
import io.github.firebirks.booruvault.net.SocksCredential
import org.mozilla.geckoview.WebExtension
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Proxy
import java.io.DataInputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * The built-in browsers' checks on GeckoView, one mode per run (the extra "mode"):
 * - rtc-on / rtc-off: the WebRTC probe's page with media.peerconnection.enabled true / false;
 * - proxy: requests to 127.0.0.1 and to a name, with every connection sent to a fake SOCKS5
 *   listener that records it and refuses it;
 * - idle-default / idle-hardened: a blank page for a minute behind the same listener, with Gecko's
 *   own preferences / with [HARDENING], to see what Gecko connects to by itself;
 * - storage: a page served from 127.0.0.1 stores a value in a private session, which is closed; a
 *   new private session looks for it;
 * - cf: the Cloudflare check, the one mode that reaches the network. The site named by the extra
 *   "url" opens in a session on screen, for someone to pass its check; once it holds a
 *   cf_clearance cookie, OkHttp asks for the same address with the cookies, under Gecko's name and
 *   under Chrome's, and without them. With the extra "socks" (Orbot's port) both go through it.
 *   Images are not loaded unless the extra "images" is 1, and the page leaves the screen as soon
 *   as the check is passed.
 * - engine: the app's own preferences (GeckoPrefs) and extension. Nothing leaves at start; the
 *   route is set while the engine runs, to the proxy, to none and back; the extension reads and
 *   sets a private session's cookies;
 * - idle-app: a blank page for three minutes with the app's preferences behind the fake listener.
 * Pages log "PROBE ..." lines to the console, which GeckoView copies to logcat; so does this
 * activity. Nothing leaves the phone: the fake listener refuses every connection.
 */
class GeckoProbeActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var runtime: GeckoRuntime
    private lateinit var view: GeckoView
    private var session: GeckoSession? = null

    private fun report(line: String) {
        Log.i(TAG, "PROBE $line")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mode = intent.getStringExtra("mode") ?: "rtc-off"
        report("mode $mode")
        val holding = holdingListener()
        val pages = pageServer()

        // An explicit config file is read in release builds too; only the default path in
        // /data/local/tmp is limited to debuggable apps.
        val prefs = linkedMapOf<String, Any>("media.peerconnection.enabled" to (mode == "rtc-on"))
        if (mode == "idle-hardened" || mode == "cf") prefs += HARDENING
        val orbot = intent.getStringExtra("socks")?.toIntOrNull()
        // No image is loaded, and nothing plays, unless the extra "images" asks for them: what a
        // site shows is not for every room a phone is tested in.
        if (mode == "cf" && intent.getStringExtra("images") != "1") {
            prefs += mapOf("permissions.default.image" to 2, "media.autoplay.default" to 5)
        }
        if (mode == "cf" && orbot != null) {
            prefs += mapOf(
                "network.proxy.type" to 1,
                "network.proxy.socks" to "127.0.0.1",
                "network.proxy.socks_port" to orbot,
                "network.proxy.socks_version" to 5,
                "network.proxy.socks_remote_dns" to true,
                "network.proxy.allow_hijacking_localhost" to true,
                "network.proxy.no_proxies_on" to "",
            )
        }
        if (mode == "proxy" || mode.startsWith("idle")) {
            val socks = fakeSocks()
            prefs += mapOf(
                "network.proxy.type" to 1,
                "network.proxy.socks" to "127.0.0.1",
                "network.proxy.socks_port" to socks.localPort,
                "network.proxy.socks_version" to 5,
                "network.proxy.socks_remote_dns" to true,
                "network.proxy.allow_hijacking_localhost" to true,
                "network.proxy.no_proxies_on" to "",
            )
        }
        // The app's own preferences with nothing listening on the proxy's port, as when Orbot is
        // down: whatever the engine sends then goes around the proxy or nowhere, and only the
        // phone's own count of the probe's traffic, which run.sh reads, can tell which.
        if (mode == "idle-closed") {
            prefs.clear()
            prefs += GeckoPrefs.initial
        }
        // What a page finds of the phone through the engine, with the app's preferences on the
        // direct route, so that the page can come from 127.0.0.1; "surface-stock" with Gecko's own.
        if (mode == "surface") {
            prefs.clear()
            prefs += GeckoPrefs.base + GeckoPrefs.route(Route.DIRECT, GeckoPrefs.NO_PORT)
        }
        // What a page's scripts read of the language and the time zone, with the Tor route's
        // language and the page on 127.0.0.1. By default as the app has them through Tor; the
        // extra "variant" tries the ways that were weighed: "now" (the header's preference alone),
        // "setlocales" (the runtime's locale alone), "fpp" (the fingerprinting protection alone).
        val variant = intent.getStringExtra("variant") ?: "app"
        if (mode == "locale") {
            prefs.clear()
            prefs += GeckoPrefs.base + GeckoPrefs.route(Route.DIRECT, GeckoPrefs.NO_PORT) + (GeckoPrefs.LANGUAGES to GeckoPrefs.TOR_LANGUAGES)
            if (variant == "fpp") {
                prefs += mapOf("privacy.fingerprintingProtection" to true, "privacy.fingerprintingProtection.overrides" to "+JSLocale,+JSDateTimeUTC")
            }
            report("locale variant $variant")
        }
        if (mode == "engine" || mode == "idle-app") {
            engine(mode, holding, pages)
            return
        }
        if (mode == "hidden") {
            hidden(pages)
            return
        }
        // With the extra "engine" set to "app", the check is passed as it is in the app: its
        // preferences, a private session, its extension.
        if (mode == "cf" && intent.getStringExtra("engine") == "app") {
            val images = prefs.filterKeys { it == "permissions.default.image" || it == "media.autoplay.default" }
            prefs.clear()
            prefs += GeckoPrefs.base + GeckoPrefs.route(Route.TOR, orbot ?: GeckoPrefs.NO_PORT) + images
            report("cf engine as the app's: ${prefs.size} preferences, a private session")
        }
        val config = File(filesDir, "gecko-config.yaml")
        // The app's environment with the app's preferences: remote settings' syncs off.
        val appSettings = mode == "idle-closed" || mode == "surface" || mode == "locale" || (mode == "cf" && intent.getStringExtra("engine") == "app")
        config.writeText(GeckoPrefs.yaml(prefs, if (appSettings) GeckoPrefs.env else emptyMap()))

        runtime = GeckoRuntime.create(this, GeckoRuntimeSettings.Builder().configFilePath(config.absolutePath).consoleOutput(true).build())
        report("gecko ${GeckoSession.getDefaultUserAgent()}")
        if (mode == "locale" && variant == "setlocales") runtime.settings.locales = arrayOf("en-US")
        // As the app sets the Tor route's page settings while the engine runs: the runtime's
        // locale first, then the preferences, and the page once they are all set.
        if (mode == "locale" && variant == "app") {
            runtime.settings.locales = GeckoPrefs.TOR_LOCALES
            fun set(rest: List<Map.Entry<String, Any>>) {
                val (name, value) = rest.firstOrNull() ?: return open("http://127.0.0.1:${pages.localPort}/locale.html")
                val result = when (value) {
                    is String -> GeckoPreferenceController.setGeckoPref(name, value, GeckoPreferenceController.PREF_BRANCH_USER)
                    is Int -> GeckoPreferenceController.setGeckoPref(name, value, GeckoPreferenceController.PREF_BRANCH_USER)
                    else -> GeckoPreferenceController.setGeckoPref(name, value as Boolean, GeckoPreferenceController.PREF_BRANCH_USER)
                }
                result.accept({ set(rest.drop(1)) }, { e -> report("locale set $name FAILED $e") })
            }
            view = GeckoView(this)
            setContentView(view)
            set(GeckoPrefs.torPage.entries.toList())
            return
        }
        view = GeckoView(this)
        setContentView(view)

        when (mode) {
            "rtc-on", "rtc-off" -> open("resource://android/assets/probe.html#port=${holding.localPort}")
            "proxy" -> open("resource://android/assets/web2.html#port=${holding.localPort}&domain=1")
            "idle-default", "idle-hardened", "idle-closed" -> {
                open("about:blank")
                main.postDelayed({ report("SUMMARY idle done") }, 60_000)
            }
            "cf" -> cloudflare(intent.getStringExtra("url") ?: return report("cf no url"), orbot)
            "surface", "surface-stock" -> open("http://127.0.0.1:${pages.localPort}/surface.html")
            "locale" -> open("http://127.0.0.1:${pages.localPort}/locale.html")
            "storage" -> {
                val page = "http://127.0.0.1:${pages.localPort}/web3.html"
                open("$page#phase=write")
                // Closed as the app's browser is when done; a later visit opens a new one.
                main.postDelayed({
                    session?.close()
                    session = null
                    report("storage.sessionClosed")
                    main.postDelayed({ open("$page#phase=read") }, 3000)
                }, 5000)
            }
        }
    }

    /**
     * A page read as the app's hidden browser reads it: a private session on no view, the cookies
     * it starts with handed over by the app's extension, no image asked for, and the page's HTML
     * through the extension's script in the page.
     */
    private fun hidden(pages: ServerSocket) {
        val config = File(filesDir, "gecko-config.yaml")
        config.writeText(GeckoPrefs.yaml(GeckoPrefs.base + GeckoPrefs.route(Route.DIRECT, GeckoPrefs.NO_PORT), GeckoPrefs.env))
        runtime = GeckoRuntime.create(this, GeckoRuntimeSettings.Builder().configFilePath(config.absolutePath).consoleOutput(true).build())
        // The screen shows a view no session is on.
        view = GeckoView(this)
        setContentView(view)
        val local = "http://127.0.0.1:${pages.localPort}/hidden.html"
        var port: WebExtension.Port? = null
        var page: WebExtension.Port? = null
        val answers = HashMap<Int, (JSONObject) -> Unit>()
        var ids = 0
        fun images(load: Boolean, then: () -> Unit) {
            GeckoPreferenceController.setGeckoPref("permissions.default.image", if (load) 1 else 2, GeckoPreferenceController.PREF_BRANCH_USER)
                .accept({ report("hidden images ${if (load) "on" else "off"}"); then() }, { e -> report("hidden images FAILED: $e") })
        }
        fun read(what: String, then: () -> Unit) {
            val id = ++ids
            answers[id] = { answer ->
                val html = answer.optString("html")
                report(
                    "hidden page ($what): state=${answer.optString("state")} first=${"id=\"first\"" in html} late=${"id=\"late\"" in html} " +
                        "cookie=${"given=1" in html} address=${answer.optString("url").substringBefore('?')} length=${html.length}",
                )
                then()
            }
            page?.postMessage(JSONObject().put("op", "html").put("id", id)) ?: report("hidden page FAILED: no line to the page")
        }
        fun visit(extension: WebExtension) {
            images(false) {
                val s = GeckoSession(GeckoSessionSettings.Builder().usePrivateMode(true).build())
                s.open(runtime)
                session = s
                s.webExtensionController.setMessageDelegate(extension, object : WebExtension.MessageDelegate {
                    override fun onConnect(connected: WebExtension.Port) {
                        page = connected
                        connected.setDelegate(object : WebExtension.PortDelegate {
                            override fun onPortMessage(message: Any, from: WebExtension.Port) {
                                val answer = message as? JSONObject ?: return
                                answers.remove(answer.optInt("id"))?.invoke(answer)
                            }
                        })
                        report("hidden page connected")
                    }
                }, "booruvault_page")
                // The session's first stop is its empty page's, which has no script to answer:
                // the step moves on only once a page has answered.
                var step = 0
                s.progressDelegate = object : GeckoSession.ProgressDelegate {
                    override fun onPageStop(session: GeckoSession, success: Boolean) {
                        main.postDelayed({
                            if (page == null) return@postDelayed
                            when (step) {
                                0 -> read("no images") { step = 1; page = null; images(true) { s.loadUri("$local?again") } }
                                1 -> read("images") { step = 2; report("SUMMARY hidden done") }
                            }
                        }, 1500)
                    }
                }
                s.setActive(true)
                val id = ++ids
                answers[id] = { answer ->
                    report("hidden setCookies error=${answer.optString("error")}")
                    s.loadUri(local)
                }
                val given = JSONObject().put("url", local).put("name", "given").put("value", "1").put("secure", false)
                port?.postMessage(JSONObject().put("op", "setCookies").put("id", id).put("cookies", org.json.JSONArray().put(given))) ?: report("hidden FAILED: extension not connected")
            }
        }
        val messages = object : WebExtension.MessageDelegate {
            override fun onConnect(connected: WebExtension.Port) {
                port = connected
                connected.setDelegate(object : WebExtension.PortDelegate {
                    override fun onPortMessage(message: Any, from: WebExtension.Port) {
                        val answer = message as? JSONObject ?: return
                        answers.remove(answer.optInt("id"))?.invoke(answer)
                    }
                })
            }
        }
        runtime.webExtensionController.ensureBuiltIn("resource://android/assets/browser-ext/", "browser@booruvault").accept(
            { extension ->
                extension?.setMessageDelegate(messages, "booruvault")
                if (extension != null) runtime.webExtensionController.setAllowedInPrivateBrowsing(extension, true).accept(
                    { allowed ->
                        allowed?.setMessageDelegate(messages, "booruvault")
                        // Gecko starts the extension again once it is let into private sessions.
                        main.postDelayed({ visit(allowed ?: extension) }, 3000)
                    },
                    { e -> report("hidden FAILED: $e") },
                )
            },
            { e -> report("hidden FAILED: $e") },
        )
    }

    /** The app's preferences and extension, tried in the order the app would use them. */
    private fun engine(mode: String, holding: ServerSocket, pages: ServerSocket) {
        val socks = fakeSocks()
        val first = if (mode == "idle-app") GeckoPrefs.base + GeckoPrefs.route(Route.TOR, socks.localPort) else GeckoPrefs.initial
        val config = File(filesDir, "gecko-config.yaml")
        config.writeText(GeckoPrefs.yaml(first, GeckoPrefs.env))
        runtime = GeckoRuntime.create(this, GeckoRuntimeSettings.Builder().configFilePath(config.absolutePath).consoleOutput(true).build())
        view = GeckoView(this)
        setContentView(view)
        report("engine prefs: ${first.size}, proxy port at start: ${first["network.proxy.socks_port"]}")
        if (mode == "idle-app") {
            open("about:blank")
            main.postDelayed({ report("SUMMARY idle done") }, 180_000)
            return
        }

        // What Java's SOCKS client says to the proxy on this phone, with the app's credential set.
        SocksCredential.install()
        thread {
            // The proxy refuses every connection, so the call fails; how it fails is said, should
            // it fail before reaching the proxy.
            runCatching { client(socks.localPort).newCall(Request.Builder().url("https://from-okhttp.invalid/").build()).execute().close() }
                .onFailure { report("engine okhttp ended: ${it.javaClass.simpleName}: ${it.message}") }
        }

        var port: WebExtension.Port? = null
        val answers = HashMap<Int, (JSONObject) -> Unit>()
        var ids = 0
        fun ask(question: JSONObject, then: (JSONObject) -> Unit) {
            val id = ++ids
            answers[id] = then
            port?.postMessage(question.put("id", id)) ?: report("engine extension not connected")
        }
        // As GeckoEngine.applyRoute does: the extension told to stop naming the proxy before a
        // direct route is set, each preference set and waited for, in order, and the extension
        // told the proxy's port and the app's credential after the route through it is set.
        fun tell(port: Int?, then: () -> Unit) {
            val question = JSONObject().put("op", "route")
            if (port != null) question.put("socks", JSONObject().put("port", port).put("username", SocksCredential.USER).put("password", SocksCredential.PASSWORD))
            ask(question) { answer ->
                if (answer.optString("error").isNotEmpty()) report("engine route FAILED in the extension: ${answer.optString("error")}")
                then()
            }
        }
        fun apply(route: Route, port: Int, then: () -> Unit) {
            // Directly, the phone's languages are given back; here, ones no phone would have.
            val prefs = GeckoPrefs.route(route, port, "eo,tlh").entries.toList()
            fun step(i: Int) {
                if (i == prefs.size) {
                    report("engine route $route applied")
                    GeckoPreferenceController.getGeckoPref(GeckoPrefs.LANGUAGES).accept(
                        { pref -> report("engine languages on $route: ${pref?.value}"); if (route == Route.TOR) tell(port, then) else then() },
                        { e -> report("engine languages FAILED: $e") },
                    )
                    return
                }
                val (name, value) = prefs[i]
                val result = when (value) {
                    is String -> GeckoPreferenceController.setGeckoPref(name, value, GeckoPreferenceController.PREF_BRANCH_USER)
                    is Int -> GeckoPreferenceController.setGeckoPref(name, value, GeckoPreferenceController.PREF_BRANCH_USER)
                    is Boolean -> GeckoPreferenceController.setGeckoPref(name, value, GeckoPreferenceController.PREF_BRANCH_USER)
                    else -> error("no such preference type")
                }
                result.accept({ step(i + 1) }, { e -> report("engine route $route FAILED at $name: $e") })
            }
            if (route == Route.DIRECT) tell(null) { step(0) } else step(0)
        }
        fun load(what: String, url: String, then: () -> Unit) {
            report("engine load ($what) $url")
            open(url)
            main.postDelayed(then, 4000)
        }
        val local = "http://127.0.0.1:${pages.localPort}/cookie.html"
        fun cookieNames(then: () -> Unit) = ask(JSONObject().put("op", "cookies").put("urls", org.json.JSONArray(listOf(local)))) { answer ->
            val list = answer.optJSONObject("cookies")?.optJSONArray(local)
            report("engine extension cookies: ${(0 until (list?.length() ?: 0)).map { list!!.getJSONObject(it).getString("name") }} error=${answer.optString("error")}")
            then()
        }
        fun steps() {
            load("sealed", "http://sealed.invalid/") {
                apply(Route.TOR, socks.localPort) {
                    load("tor, a name", "http://through-tor.invalid/") {
                        load("tor, the phone itself", local) {
                            apply(Route.DIRECT, socks.localPort) {
                                load("direct", local) {
                                    cookieNames {
                                        ask(JSONObject().put("op", "setCookies").put("cookies", org.json.JSONArray().put(JSONObject().put("url", local).put("name", "given").put("value", "1").put("secure", false)))) { answer ->
                                            report("engine extension setCookies error=${answer.optString("error")}")
                                            load("direct, again", local) {
                                                cookieNames {
                                                    apply(Route.TOR, socks.localPort) {
                                                        load("tor, again", "http://back-on-tor.invalid/") { report("SUMMARY engine done") }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        runtime.webExtensionController.ensureBuiltIn("resource://android/assets/browser-ext/", "browser@booruvault").accept(
            { extension ->
                extension?.setMessageDelegate(object : WebExtension.MessageDelegate {
                    override fun onConnect(connected: WebExtension.Port) {
                        port = connected
                        connected.setDelegate(object : WebExtension.PortDelegate {
                            override fun onPortMessage(message: Any, from: WebExtension.Port) {
                                val answer = message as? JSONObject ?: return
                                answers.remove(answer.optInt("id"))?.invoke(answer)
                            }
                        })
                        report("engine extension connected")
                    }
                }, "booruvault")
                if (extension != null) runtime.webExtensionController.setAllowedInPrivateBrowsing(extension, true).accept({ steps() }, { e -> report("engine private browsing FAILED: $e") })
            },
            { e -> report("engine extension FAILED: $e") },
        )
    }

    /** Opens [url] for a person to pass its check, then has OkHttp send on what the browser holds. */
    private fun cloudflare(url: String, orbot: Int?) {
        report("cf route ${if (orbot != null) "socks 127.0.0.1:$orbot" else "direct"}")
        // As the app does: a request of its own before the check, whose connection stays in the pool.
        intent.getStringExtra("before")?.let { first ->
            early = client(orbot)
            thread {
                val line = runCatching {
                    early!!.newCall(Request.Builder().url(first).header("User-Agent", "BooruVault").build()).execute().use { "${it.code} cf-mitigated=${it.header("cf-mitigated")}" }
                }.getOrElse { "failed: $it" }
                report("cf okhttp before the check, the app's name: $line (connections opened: ${opened.get()})")
            }
        }
        var replayed = false
        var port: WebExtension.Port? = null
        val asApp = intent.getStringExtra("engine") == "app"
        fun ask() {
            port?.postMessage(if (asApp) JSONObject().put("id", 1).put("op", "cookies").put("urls", org.json.JSONArray(listOf(url))) else JSONObject().put("url", url))
        }
        val onMessage = object : WebExtension.PortDelegate {
            override fun onPortMessage(message: Any, from: WebExtension.Port) {
                val answer = message as? JSONObject ?: return
                if (answer.optString("event") == "status") return
                val cookies = if (asApp) answer.optJSONObject("cookies")?.optJSONArray(url) ?: org.json.JSONArray() else answer.getJSONArray("cookies")
                val names = (0 until cookies.length()).map { cookies.getJSONObject(it).getString("name") }
                if (replayed) return
                if ("cf_clearance" !in names) {
                    report("cf waiting, cookies: $names")
                    main.postDelayed({ ask() }, 3000)
                    return
                }
                replayed = true
                // The check is passed: the page goes off the screen at once.
                main.post {
                    session?.close()
                    session = null
                    setContentView(android.view.View(this@GeckoProbeActivity))
                }
                val header = (0 until cookies.length()).joinToString("; ") { cookies.getJSONObject(it).run { "${getString("name")}=${getString("value")}" } }
                val gecko = if (asApp) GeckoSession.getDefaultUserAgent() else answer.getString("agent")
                report("cf cleared, cookies: $names, agent: $gecko")
                thread { replay(url, orbot, gecko, header) }
            }
        }
        val messages = object : WebExtension.MessageDelegate {
            override fun onConnect(connected: WebExtension.Port) {
                port = connected
                connected.setDelegate(onMessage)
                main.postDelayed({ ask() }, 3000)
            }
        }
        fun load(private: Boolean) {
            val s = GeckoSession(GeckoSessionSettings.Builder().usePrivateMode(private).build())
            s.open(runtime)
            view.setSession(s)
            session = s
            s.loadUri(url)
        }
        if (asApp) {
            runtime.webExtensionController.ensureBuiltIn("resource://android/assets/browser-ext/", "browser@booruvault").accept(
                { extension ->
                    extension?.setMessageDelegate(messages, "booruvault")
                    if (extension != null) runtime.webExtensionController.setAllowedInPrivateBrowsing(extension, true).accept({ allowed ->
                        allowed?.setMessageDelegate(messages, "booruvault")
                        load(private = true)
                    }, { e -> report("cf extension failed: $e") })
                },
                { e -> report("cf extension failed: $e") },
            )
            return
        }
        runtime.webExtensionController.ensureBuiltIn("resource://android/assets/cookies-ext/", "cookies@probe.booruvault").accept(
            { extension ->
                extension?.setMessageDelegate(messages, "probe")
                // Not private: the extension reads the default cookie store.
                load(private = false)
            },
            { e -> report("cf extension failed: $e") },
        )
    }

    private var early: OkHttpClient? = null
    private val opened = java.util.concurrent.atomic.AtomicInteger()

    private fun client(orbot: Int?): OkHttpClient = OkHttpClient.Builder()
        .apply { if (orbot != null) proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved("127.0.0.1", orbot))) }
        .eventListener(object : okhttp3.EventListener() {
            override fun connectStart(call: okhttp3.Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
                opened.incrementAndGet()
            }
        })
        .followRedirects(false)
        .build()

    private fun replay(url: String, orbot: Int?, gecko: String, cookies: String) {
        // The connection the app's first request left in the pool, then a new one.
        early?.let { old ->
            val first = intent.getStringExtra("before")!!
            for ((name, c) in listOf("the connection from before the check" to old, "a new connection" to client(orbot))) {
                val before = opened.get()
                val line = runCatching {
                    c.newCall(Request.Builder().url(first).header("User-Agent", gecko).header("Cookie", cookies).build()).execute().use { "${it.code} cf-mitigated=${it.header("cf-mitigated")}" }
                }.getOrElse { "failed: $it" }
                report("cf okhttp on $name, gecko's name and cookies: $line (opened ${opened.get() - before} connection)")
            }
        }
        // Java's SOCKS client names itself to the proxy with a username and Gecko names nobody, so
        // Tor keeps the two on different circuits, most often with different exits: a clearance
        // that holds here is not bound to the address that earned it.
        val client = OkHttpClient.Builder()
            .apply { if (orbot != null) proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved("127.0.0.1", orbot))) }
            .followRedirects(false)
            .build()
        val chrome = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/157.0.0.0 Mobile Safari/537.36"
        fun attempt(name: String, address: String, agent: String, cookie: String?) {
            val request = Request.Builder().url(address).header("User-Agent", agent).apply { if (cookie != null) header("Cookie", cookie) }.build()
            val line = runCatching {
                client.newCall(request).execute().use { "${it.code} cf-mitigated=${it.header("cf-mitigated")} ${it.protocol}" }
            }.getOrElse { "failed: $it" }
            report("cf okhttp $name: $line")
        }
        attempt("the address passed, gecko's name and cookies", url, gecko, cookies)
        // As the app asks: the API with its query, and the site's own origin as Referer.
        intent.getStringExtra("api")?.let { api ->
            attempt("the api, plainly", api, gecko, cookies)
            val line = runCatching {
                client.newCall(Request.Builder().url(api).header("User-Agent", gecko).header("Referer", Regex("^(https?://[^/]+)").find(api)!!.value + "/").header("Cookie", cookies).build())
                    .execute().use { "${it.code} cf-mitigated=${it.header("cf-mitigated")} ${it.protocol}" }
            }.getOrElse { "failed: $it" }
            report("cf okhttp the api, with the app's Referer: $line")
        }
        // Other addresses of the site, given in the extra "then": a clearance earned on one page
        // may not be taken on another that a stricter rule guards.
        intent.getStringExtra("then")?.split(' ')?.filter { it.isNotBlank() }?.forEach { other ->
            attempt("then $other, gecko's name and cookies", other, gecko, cookies)
        }
        attempt("the address passed, chrome's name and cookies", url, chrome, cookies)
        attempt("the address passed, gecko's name, no cookies", url, gecko, null)
        report("SUMMARY cf done")
    }

    private fun open(url: String) {
        val s = GeckoSession(GeckoSessionSettings.Builder().usePrivateMode(true).build())
        s.open(runtime)
        view.setSession(s)
        session = s
        s.loadUri(url)
    }

    /** Takes each connection and never answers it, reporting it: a frame sent here stays on its first document. */
    private fun holdingListener(): ServerSocket = listener { socket ->
        val line = runCatching { socket.getInputStream().bufferedReader().readLine() }.getOrNull()
        report("loopback.hit ${line ?: "(nothing read)"}")
        runCatching { Thread.sleep(120_000) }
    }

    /** Serves the assets over http://127.0.0.1, a real origin, as a site's pages would be. */
    private fun pageServer(): ServerSocket = listener { socket ->
        val reader = socket.getInputStream().bufferedReader()
        val path = reader.readLine()?.split(' ')?.getOrNull(1).orEmpty().substringBefore('?').trimStart('/')
        var languages: String? = null
        while (true) {
            val header = reader.readLine()?.takeIf { it.isNotEmpty() } ?: break
            if (header.startsWith("Accept-Language:", ignoreCase = true)) languages = header.substringAfter(':').trim()
        }
        val body = runCatching { assets.open(path).use { it.readBytes() } }.getOrNull()
        report("page.hit /$path${if (languages != null && path.endsWith(".html")) " asked in $languages" else ""}")
        val head = if (body != null) {
            "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nSet-Cookie: served=1; Path=/\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
        } else {
            "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
        }
        socket.getOutputStream().apply {
            write(head.toByteArray())
            body?.let(::write)
            flush()
        }
    }

    /** A SOCKS5 listener that records each CONNECT and refuses it: nothing goes further. */
    private fun fakeSocks(): ServerSocket = listener { socket ->
        val input = DataInputStream(socket.getInputStream())
        val output = socket.getOutputStream()
        check(input.readUnsignedByte() == 5)
        val methods = List(input.readUnsignedByte()) { input.readUnsignedByte() }
        // As Tor does: username and password whenever they are offered.
        var user: String? = null
        if (2 in methods) {
            output.write(byteArrayOf(5, 2))
            check(input.readUnsignedByte() == 1)
            user = String(ByteArray(input.readUnsignedByte()).also(input::readFully))
            val password = String(ByteArray(input.readUnsignedByte()).also(input::readFully))
            user = "$user/$password"
            output.write(byteArrayOf(1, 0))
        } else {
            output.write(byteArrayOf(5, 0))
        }
        check(input.readUnsignedByte() == 5)
        input.readUnsignedByte()
        input.readUnsignedByte()
        val type = input.readUnsignedByte()
        val host = when (type) {
            1 -> ByteArray(4).also(input::readFully).joinToString(".") { (it.toInt() and 0xff).toString() }
            3 -> String(ByteArray(input.readUnsignedByte()).also(input::readFully))
            else -> ByteArray(16).also(input::readFully).joinToString(":") { "%02x".format(it) }
        }
        val port = input.readUnsignedShort()
        report("socks.connect atyp=$type $host:$port${if (user != null) " credential=$user" else ""}")
        output.write(byteArrayOf(5, 5, 0, 1, 0, 0, 0, 0, 0, 0))
    }

    private fun listener(handle: (java.net.Socket) -> Unit): ServerSocket {
        val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            while (true) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) {
                    runCatching { handle(socket) }
                    runCatching { socket.close() }
                }
            }
        }
        return server
    }

    private companion object {
        const val TAG = "GeckoProbe"

        /** What Gecko connects to by itself, turned off: with these, a minute idle saw no connection. */
        val HARDENING = mapOf<String, Any>(
            "network.captive-portal-service.enabled" to false,
            "network.connectivity-service.enabled" to false,
            "browser.safebrowsing.malware.enabled" to false,
            "browser.safebrowsing.phishing.enabled" to false,
            "browser.safebrowsing.downloads.enabled" to false,
            "browser.safebrowsing.update.enabled" to false,
            "services.settings.server" to "data:,",
            "network.dns.disablePrefetch" to true,
            "network.prefetch-next" to false,
            "network.predictor.enabled" to false,
            "dom.push.enabled" to false,
            "toolkit.telemetry.enabled" to false,
            "datareporting.healthreport.uploadEnabled" to false,
            "extensions.update.enabled" to false,
            "app.update.enabled" to false,
        )
    }
}
