package io.github.firebirks.booruvault.net

import android.content.Context
import android.os.LocaleList
import androidx.annotation.OptIn
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import io.github.firebirks.booruvault.util.debugNoImages
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Cookie
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.ExperimentalGeckoViewApi
import org.mozilla.geckoview.GeckoPreferenceController
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.StorageController
import org.mozilla.geckoview.WebExtension
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * GeckoView as the built-in browsers use it: one runtime for the process, made on first use, with
 * the preferences of [GeckoPrefs] and an extension of the app's own for what GeckoView has no
 * call for, cookies above all. Every call is made on the main thread, where the runtime lives.
 *
 * Preferences are set while the engine runs through [GeckoPreferenceController], which GeckoView
 * marks as experimental: it may change with an update of the engine, and `tools/gecko-probe`
 * tries the route it sets on every one.
 */
@OptIn(markerClass = [ExperimentalGeckoViewApi::class])
class GeckoEngine(private val context: Context) {
    private var made: GeckoRuntime? = null
    // Whether the runtime was found holding the preferences it was started with: see holdPrefs.
    private var held = false
    // One route at a time is set on the engine: a browser's, or the one that leads nowhere.
    private val routing = Mutex()
    // The extension's line to the app. Gecko starts the extension again when it is let into
    // private sessions, and the line it had opened before is dead: the last one counts, and only
    // once that is settled.
    private val port = MutableStateFlow<WebExtension.Port?>(null)
    private val extensionReady = CompletableDeferred<WebExtension>()
    private val _statuses = MutableSharedFlow<PageStatus>(extraBufferCapacity = 16)

    /** The status each page came back with, as the extension tells it: GeckoView has no call for it. */
    val statuses: SharedFlow<PageStatus> = _statuses.asSharedFlow()

    /** A page's answer: its [status], and whether it is an anti-bot check's own page ([challenge]). */
    class PageStatus(val url: String, val status: Int, val challenge: Boolean)
    private val asked = ConcurrentHashMap<Int, CompletableDeferred<String>>()
    private val ids = AtomicInteger()

    /** The runtime, made the first time it is asked for. Nothing leaves it until [applyRoute] says how. */
    val runtime: GeckoRuntime
        get() = made ?: create().also { made = it }

    private fun create(): GeckoRuntime {
        // In clear, as it holds the engine's preferences and nothing of the user's.
        val config = File(context.filesDir, "gecko-config.yaml")
        config.writeText(GeckoPrefs.yaml(GeckoPrefs.initial + testPrefs(), GeckoPrefs.env))
        val settings = GeckoRuntimeSettings.Builder()
            .configFilePath(config.absolutePath)
            // Neither the pages' console nor the engine's own messages, which name the addresses
            // pages load and the scripts that fail on them, go to the system log.
            .consoleOutput(false)
            .debugLogging(false)
            // Pages run in processes Android isolates: no network, none of the app's files, no
            // service of the app's to bind. Otherwise a page that took its process over would be
            // the app, and could connect around Orbot or rewrite the engine's configuration file.
            .isolatedProcessEnabled(true)
            .remoteDebuggingEnabled(false)
            .loginAutofillEnabled(false)
            .webManifest(false)
            .contentBlocking(
                ContentBlocking.Settings.Builder()
                    .safeBrowsing(ContentBlocking.SafeBrowsing.NONE)
                    // A site's own cookies and no one else's, as the settings promise of the
                    // browsers; the engine would otherwise keep third parties' apart for each site.
                    .cookieBehavior(ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY)
                    .cookieBehaviorPrivateMode(ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY)
                    .build(),
            )
            .build()
        val runtime = GeckoRuntime.create(context, settings)
        val messages = object : WebExtension.MessageDelegate {
            override fun onConnect(connected: WebExtension.Port) {
                connected.setDelegate(object : WebExtension.PortDelegate {
                    override fun onPortMessage(message: Any, from: WebExtension.Port) {
                        val answer = message as? JSONObject ?: return
                        if (answer.optString("event") == "status") {
                            _statuses.tryEmit(PageStatus(answer.optString("url"), answer.optInt("status"), answer.optBoolean("challenge")))
                            return
                        }
                        asked.remove(answer.optInt("id"))?.complete(answer.toString())
                    }

                    override fun onDisconnect(from: WebExtension.Port) {
                        port.compareAndSet(from, null)
                    }
                })
                port.value = connected
            }
        }
        fun failed(e: Throwable?) {
            extensionReady.completeExceptionally(e ?: IllegalStateException("the engine's extension did not load"))
        }
        runtime.webExtensionController.ensureBuiltIn(EXTENSION, EXTENSION_ID).accept(
            { extension ->
                if (extension == null) return@accept failed(null)
                extension.setMessageDelegate(messages, NATIVE_APP)
                // Sessions are private ones, and their cookies are in the private store.
                runtime.webExtensionController.setAllowedInPrivateBrowsing(extension, true).accept(
                    { allowed ->
                        allowed?.setMessageDelegate(messages, NATIVE_APP)
                        extensionReady.complete(allowed ?: extension)
                    },
                    ::failed,
                )
            },
            ::failed,
        )
        return runtime
    }

    /** In a debug build told to show no images, the engine loads none and plays nothing: see [debugNoImages]. */
    private fun testPrefs(): Map<String, Any> =
        if (debugNoImages(context)) mapOf("permissions.default.image" to 2, "media.autoplay.default" to 5) else emptyMap()

    /** A session that keeps nothing once it is closed, opened on the runtime. */
    fun newSession(): GeckoSession = GeckoSession(
        GeckoSessionSettings.Builder()
            .usePrivateMode(true)
            .suspendMediaWhenInactive(true)
            .build(),
    ).also { it.open(runtime) }

    /**
     * Sets the engine's proxy for the route in force, before any page loads, and says which it
     * is: what the page earns belongs to that route. With Tor on nothing loads until the exit is
     * verified, and without it an onion address is refused.
     */
    suspend fun applyRoute(tor: TorManager, host: String? = null): Result<Route> = withContext(Dispatchers.Main) {
        if (!tor.routeChosen.value) return@withContext Result.failure(RouteNotChosenException())
        if (!tor.enabled && host != null && isOnion(host)) return@withContext Result.failure(OnionWithoutTorException(host))
        if (tor.enabled && !tor.verified) {
            tor.refreshStatus()
            return@withContext Result.failure(TorUnverifiedException())
        }
        val route = tor.route
        val port = tor.state.value.socksPort
        routing.withLock {
            runCatching {
                // An engine that cannot start fails the route, and the browsers say so, rather
                // than take the app down: GeckoView lets an Error from its start through.
                runtime
                holdPrefs()
                // The extension stops naming Orbot's port before the preferences turn the proxy off:
                // told nothing, it leaves each request to the preferences.
                if (route == Route.DIRECT) tellRoute(null)
                // The engine's locale goes with the language: English before anything goes
                // through the proxy, the phone's once it is off. GeckoView asks in the locale's
                // languages and the phone's after them, so the language preference is set after it.
                if (route == Route.TOR) runtime.settings.locales = GeckoPrefs.TOR_LOCALES
                for ((name, value) in GeckoPrefs.route(route, port, phoneLanguages())) setUserPref(name, value)
                if (route == Route.DIRECT) runtime.settings.locales = phoneLocales()
                if (route == Route.TOR) {
                    // GeckoView sets the language once more as the runtime starts, from the phone's,
                    // and its message may come after the route's: through Tor it must not stand.
                    val asked = GeckoPreferenceController.getGeckoPref(GeckoPrefs.LANGUAGES).await()?.value
                    check(asked == GeckoPrefs.TOR_LANGUAGES) { "the engine asks pages in $asked through Tor" }
                    tellRoute(port)
                }
                route
            }
        }
    }

    /**
     * After a route switch: an engine already running goes back to the proxy that leads nowhere,
     * and the extension stops naming Orbot, until a browser applies the route then in force. Left
     * on the route it had, it would carry on along it whatever it sends by itself, and a page of a
     * visit the switch stops until its session is closed.
     */
    suspend fun park() = withContext(Dispatchers.Main) {
        if (made == null) return@withContext
        routing.withLock {
            runCatching {
                tellRoute(null)
                for ((name, value) in GeckoPrefs.startProxy) setUserPref(name, value)
            }
        }
        Unit
    }

    private suspend fun setUserPref(name: String, value: Any) {
        when (value) {
            is String -> GeckoPreferenceController.setGeckoPref(name, value, GeckoPreferenceController.PREF_BRANCH_USER)
            is Int -> GeckoPreferenceController.setGeckoPref(name, value, GeckoPreferenceController.PREF_BRANCH_USER)
            is Boolean -> GeckoPreferenceController.setGeckoPref(name, value, GeckoPreferenceController.PREF_BRANCH_USER)
            else -> error("no such preference type")
        }.await()
    }

    /**
     * Before the runtime's first route: puts the start-up proxy back, which the last route's
     * preferences, kept from the engine's last start, would otherwise stand in for, and checks
     * that the engine holds [GeckoPrefs.base]. GeckoView reads those from the file it is started
     * with, and starts with its own should it fail to: WebRTC on, the proxy with ways around it,
     * connections of its own. A browser that opened then would look as it should and not be, so
     * it does not open. Once for each runtime, as only the app sets them.
     */
    private suspend fun holdPrefs() {
        if (held) return
        for (name in GeckoPrefs.startProxy.keys) runCatching { GeckoPreferenceController.clearGeckoUserPref(name).await() }
        val found = GeckoPreferenceController.getGeckoPrefs(GeckoPrefs.base.keys.toList()).await().orEmpty()
            .filter { it.type != GeckoPreferenceController.PREF_TYPE_INVALID }
            .associate { it.pref to it.value }
        val wrong = GeckoPrefs.notHeld(found)
        check(wrong.isEmpty()) { "the engine does not hold its preferences: ${wrong.joinToString()}" }
        held = true
    }

    /** The phone's languages, as the engine asks pages in them when left to itself: each one's tag, in the user's order. */
    private fun phoneLanguages(): String = phoneLocales().joinToString(",")

    private fun phoneLocales(): Array<String> = LocaleList.getDefault().let { list -> Array(list.size()) { list[it].toLanguageTag() } }

    /**
     * Has the extension send each request to Orbot's [port] with the app's [SocksCredential],
     * which preferences cannot say, or with null leave requests to the preferences alone. The
     * preferences stay what decides the route: the extension only ever names Orbot, never a
     * direct way. An extension that does not answer fails the route, since what it would go on
     * doing is then unknown.
     */
    private suspend fun tellRoute(port: Int?) {
        val socks = port?.let { JSONObject().put("port", it).put("username", SocksCredential.USER).put("password", SocksCredential.PASSWORD) }
        val answer = JSONObject(ask(JSONObject().put("op", "route").apply { if (socks != null) put("socks", socks) }))
        check(answer.optString("error").isEmpty()) { "the engine's extension refused the route: ${answer.optString("error")}" }
    }

    /** The cookies the sessions hold for [urls] of [site], as the app keeps them. */
    suspend fun cookiesFor(site: String, vararg urls: String): List<Cookie> {
        val answer = ask(JSONObject().put("op", "cookies").put("urls", JSONArray(urls.toList())))
        return BrowserCookies.parse(answer).of(site, System.currentTimeMillis() + KEEP_MS)
    }

    /**
     * Hands the sessions the cookies the vault keeps for [url], before the page loads: each one
     * HttpOnly, whatever the site set. Reading a page needs no script of it to reach a login, and a
     * script that cannot reach a clearance cannot write one over it either.
     */
    suspend fun setCookies(url: String, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        val list = JSONArray()
        cookies.forEach {
            val cookie = JSONObject().put("url", url).put("name", it.name).put("value", it.value).put("secure", it.secure).put("httpOnly", true)
            if (!it.hostOnly) cookie.put("domain", it.domain)
            list.put(cookie)
        }
        ask(JSONObject().put("op", "setCookies").put("cookies", list))
    }

    /**
     * Whether the engine loads images at all: off for the hidden browser's visits, which read a
     * page's text. One switch for the engine, so the built-in browser and the hidden one are never
     * up together (see [Challenge.visibleOpen]) and each visit sets it back.
     */
    suspend fun loadImages(load: Boolean) = withContext(Dispatchers.Main) {
        // Only on a runtime [applyRoute] made: one started here would take whatever route the
        // engine last kept.
        if (made == null || debugNoImages(context)) return@withContext
        runCatching { GeckoPreferenceController.setGeckoPref("permissions.default.image", if (load) 1 else 2, GeckoPreferenceController.PREF_BRANCH_USER).await() }
        Unit
    }

    /**
     * Listens to [session]'s pages: GeckoView has no call that returns what a page holds, so the
     * extension's script in each page hands its HTML over when asked, and only on a session
     * listened to here.
     */
    suspend fun pagesOf(session: GeckoSession): PageReader = withContext(Dispatchers.Main) {
        runtime
        PageReader().also { session.webExtensionController.setMessageDelegate(extensionReady.await(), it, NATIVE_PAGE) }
    }

    /** The line to the page a session shows now; each page opens its own as it loads. */
    inner class PageReader : WebExtension.MessageDelegate {
        private val line = MutableStateFlow<WebExtension.Port?>(null)
        private val answers = ConcurrentHashMap<Int, CompletableDeferred<Page>>()

        override fun onConnect(connected: WebExtension.Port) {
            connected.setDelegate(object : WebExtension.PortDelegate {
                override fun onPortMessage(message: Any, from: WebExtension.Port) {
                    val answer = message as? JSONObject ?: return
                    val html = answer.optString("html")
                    // The content script says when a page is larger than the app reads; a page
                    // that says nothing of it is measured here.
                    val tooLarge = answer.optBoolean("tooLarge") || html.length > MAX_PAGE_CHARS
                    answers.remove(answer.optInt("id"))?.complete(
                        Page(answer.optString("url"), if (tooLarge) "" else html, answer.optString("state") == "complete", tooLarge),
                    )
                }

                override fun onDisconnect(from: WebExtension.Port) {
                    line.compareAndSet(from, null)
                }
            })
            line.value = connected
        }

        /** The address and the HTML of the page shown now, or null if it does not answer in time. */
        suspend fun read(): Page? = withContext(Dispatchers.Main) {
            val id = ids.incrementAndGet()
            val answer = CompletableDeferred<Page>()
            answers[id] = answer
            try {
                withTimeoutOrNull(PAGE_MS) {
                    line.filterNotNull().first().postMessage(JSONObject().put("op", "html").put("id", id))
                    answer.await()
                }
            } finally {
                answers.remove(id)
            }
        }
    }

    /**
     * A page as its own script tells it: [complete] once it has finished loading, and without its
     * HTML if it is [tooLarge], larger than the app's client reads of any page.
     */
    class Page(val url: String, val html: String, val complete: Boolean, val tooLarge: Boolean = false)

    /**
     * Has [session]'s pages' lines to the app closed as they open: the content script opens one in
     * every page, and one the app does not listen to stays queued, with the page's address, for
     * the life of the process. For the built-in browser, whose pages the app does not read.
     */
    suspend fun ignorePages(session: GeckoSession) = withContext(Dispatchers.Main) {
        val extension = runCatching { withTimeoutOrNull(ANSWER_MS) { extensionReady.await() } }.getOrNull() ?: return@withContext
        session.webExtensionController.setMessageDelegate(
            extension,
            object : WebExtension.MessageDelegate {
                override fun onConnect(port: WebExtension.Port) = port.disconnect()
            },
            NATIVE_PAGE,
        )
    }

    /** Deletes whatever the sessions left, though private ones keep nothing once closed. */
    suspend fun wipe() = withContext(Dispatchers.Main) {
        // No runtime, nothing left: none is started for it.
        val runtime = made ?: return@withContext
        clearPrivateCookies()
        runCatching { runtime.storageController.clearData(StorageController.ClearFlags.ALL).await() }
        Unit
    }

    /**
     * Empties the private sessions' cookie store, which the runtime's own wipe does not touch: it
     * goes only with the last private session, and one still open hands what it holds to the next.
     * Called before a session takes its first page, and in [wipe].
     */
    suspend fun clearPrivateCookies() {
        if (made == null) return
        runCatching { ask(JSONObject().put("op", "clearPrivate")) }
    }

    private suspend fun ask(question: JSONObject): String = withContext(Dispatchers.Main) {
        runtime
        val id = ids.incrementAndGet()
        val answer = CompletableDeferred<String>()
        asked[id] = answer
        try {
            withTimeout(ANSWER_MS) {
                extensionReady.await()
                port.filterNotNull().first().postMessage(question.put("id", id))
                answer.await()
            }
        } finally {
            asked.remove(id)
        }
    }

    private suspend fun <T> GeckoResult<T>.await(): T? = suspendCancellableCoroutine { continuation ->
        accept({ value -> continuation.resume(value) }, { e -> continuation.resumeWithException(e ?: IllegalStateException("the engine refused")) })
    }

    private companion object {
        const val EXTENSION = "resource://android/assets/browser-ext/"
        const val EXTENSION_ID = "browser@booruvault"
        const val NATIVE_APP = "booruvault"
        const val NATIVE_PAGE = "booruvault_page"
        const val PAGE_MS = 5_000L
        // What the app's client reads of any page, in characters: see MAX_TEXT_BODY.
        const val MAX_PAGE_CHARS = 8 shl 20
        const val ANSWER_MS = 10_000L
        const val KEEP_MS = 30L * 24 * 60 * 60 * 1000
    }
}
