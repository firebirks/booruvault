package io.github.firebirks.booruvault.net

import android.content.Context
import android.net.Uri
import android.webkit.WebSettings
import io.github.firebirks.booruvault.booru.UserAgents
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** What the built-in browsers share: where a session may go, and what a guarded file is. */
object Challenge {
    /**
     * Reads the phone's Chrome version off the system's WebView once, for the names the app's
     * HTTP client goes by (see [UserAgents]). Nothing is loaded in a WebView: the app has none.
     */
    fun learnChromeVersion(context: Context) {
        if (learned) return
        val default = runCatching { WebSettings.getDefaultUserAgent(context) }.getOrNull() ?: return
        Regex("Chrome/(\\d+)").find(default)?.groupValues?.get(1)?.let { UserAgents.chrome = it }
        learned = true
    }

    @Volatile
    private var learned = false

    private val _visibleOpen = MutableStateFlow(false)

    /**
     * Whether the built-in browser is on screen. The engine's private sessions share one cookie
     * store: while the user is at a check in that browser, nothing else fills or empties it.
     * The [HiddenBrowser] waits for it to close, and gives way to it.
     */
    val visibleOpen: StateFlow<Boolean> = _visibleOpen.asStateFlow()

    fun visibleOpened() {
        _visibleOpen.value = true
    }

    fun visibleClosed() {
        _visibleOpen.value = false
    }

    /** Cloudflare's checks run in a frame from here; it is the one host outside a site a session may reach. */
    const val CHALLENGE_HOST = "challenges.cloudflare.com"

    /**
     * Whether a built-in browser session started on [site] (a registrable domain) may navigate
     * to [url]: the site itself, over https or the scheme it was opened with, and Cloudflare's
     * challenge host. Anything else is where a page's script or link tries to take the user,
     * and a password typed there would go to a site the bar does not name.
     */
    fun allowedNavigation(site: String, startScheme: String, url: Uri): Boolean {
        val host = url.host?.lowercase() ?: return false
        val scheme = url.scheme?.lowercase() ?: return false
        if (scheme != "https" && scheme != startScheme) return false
        return host == CHALLENGE_HOST || SecureCookieJar.siteOf(host) == site
    }

    /**
     * Sent with the browser's first request for a guarded file: a range that starts past the end of
     * any file. A host that puts a check in front of it answers with the check's page; one that lets
     * the browser through answers 416, without a byte of the file.
     */
    const val NO_BYTES_RANGE = "bytes=1099511627776-"

    /**
     * Whether the page's own request for a guarded file is refused and made again with
     * [NO_BYTES_RANGE], given whether the session holds a clearance ([held], null when the engine
     * did not say). Only a session known to hold none is let through, as a host that asked for no
     * check: one that did not say may hold one, and the file would then come whole.
     */
    fun refusesOwnRequest(held: Boolean?): Boolean = held != false

    /** Whether [url] asks for the very file at [target]: same host and path, whatever query a check adds. */
    fun isGuardedFile(target: String, url: String): Boolean {
        val guarded = target.toHttpUrlOrNull() ?: return false
        val asked = url.toHttpUrlOrNull() ?: return false
        return guarded.host == asked.host && guarded.encodedPath == asked.encodedPath
    }

    /**
     * The cookie [name] that a browser session holds for [url] of [site], as the app keeps it: a
     * clearance for the whole site, whose other hosts check it too, anything else on the host of
     * the address it was found at. Secure when the page was, so that a board served over plain
     * http is sent back what it set, as a browser would send it. A clearance counts only
     * [httpOnly], as Cloudflare sets it: one a page's script could reach is one it may have
     * written, and kept it would be sent as a pass, under the browser's name, for thirty days.
     */
    internal fun browserCookie(name: String, value: String, url: HttpUrl, site: String, expires: Long, httpOnly: Boolean): Cookie? = runCatching {
        if (!sendable(name, value)) return null
        if (name == CLEARANCE_COOKIE && !httpOnly) return null
        Cookie.Builder().name(name).value(value).path("/").expiresAt(expires)
            .apply {
                if (httpOnly) httpOnly()
                if (url.isHttps) secure()
                if (name == CLEARANCE_COOKIE) domain(site) else hostOnlyDomain(url.host.lowercase())
            }
            .build()
    }.getOrNull()

    const val CLEARANCE_COOKIE = "cf_clearance"

    /**
     * Whether a cookie [name] with [value] can go out in a request: printable ASCII, and no `;`,
     * which would end it. The client writes headers in nothing else, and throws on anything else,
     * which on its own threads ends the app. The engine takes a cookie with bytes past ASCII, as a
     * page's script may set it: kept, it would end every request to its site.
     */
    internal fun sendable(name: String, value: String): Boolean =
        name.isNotEmpty() && name.all { it in '!'..'~' && it != '=' && it != ';' } && value.all { it in ' '..'~' && it != ';' }

    /**
     * Deletes from disk the directories the system's WebView leaves: a version of the app that
     * had its browsers on it may have left a session's there, and asking it for Chrome's version
     * makes them again. Run at start-up.
     */
    fun wipeLeftovers(context: Context) {
        listOf(
            java.io.File(context.dataDir, "app_webview"),
            java.io.File(context.dataDir, "app_textures"),
            java.io.File(context.cacheDir, "WebView"),
        ).forEach { it.deleteRecursively() }
    }
}
