package io.github.firebirks.booruvault.net

import android.content.Context
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebResourceResponse
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewDatabase
import io.github.firebirks.booruvault.booru.BooruSource
import io.github.firebirks.booruvault.booru.UserAgents
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.ByteArrayInputStream

/** The WebView side of anti-bot checks. The WebView keeps nothing once a check is over. */
object Challenge {
    /**
     * The built-in browser's User-Agent: Chrome's WebView form of the app's own name, with the
     * real Chrome version but "Android 10; K" in place of the phone model and Android version.
     * It is also what the app presents to a site after its check is passed.
     */
    fun userAgent(context: Context, source: BooruSource?): String {
        learnChromeVersion(context)
        return UserAgents.forSource(source, webView = true)
    }

    /** Reads the phone's Chrome version off the WebView once, for every name the app uses. */
    fun learnChromeVersion(context: Context) {
        if (learned) return
        val default = runCatching { WebSettings.getDefaultUserAgent(context) }.getOrNull() ?: return
        Regex("Chrome/(\\d+)").find(default)?.groupValues?.get(1)?.let { UserAgents.chrome = it }
        learned = true
    }

    @Volatile
    private var learned = false

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

    fun hasClearance(url: String): Boolean =
        runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()?.contains("cf_clearance=") == true

    /**
     * Sent with the browser's first request for a guarded file: a range that starts past the end of
     * any file. A host that puts a check in front of it answers with the check's page; one that lets
     * the browser through answers 416, without a byte of the file.
     */
    const val NO_BYTES_RANGE = "bytes=1099511627776-"

    /** Whether [url] asks for the very file at [target]: same host and path, whatever query a check adds. */
    fun isGuardedFile(target: String, url: String): Boolean {
        val guarded = target.toHttpUrlOrNull() ?: return false
        val asked = url.toHttpUrlOrNull() ?: return false
        return guarded.host == asked.host && guarded.encodedPath == asked.encodedPath
    }

    /**
     * Whether the browser answers [url] itself, with nothing: the guarded file asked for once its
     * check is passed. The file then never reaches the browser, its cache or the screen.
     */
    fun answersEmpty(target: String, url: String, cleared: () -> Boolean): Boolean = isGuardedFile(target, url) && cleared()

    /** That empty answer. */
    fun emptyResponse(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", 204, "No Content", mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(ByteArray(0)))

    /**
     * The cookies the WebView holds for [urls] of [site]. CookieManager tells only names and
     * values, so the rest is chosen as narrowly as the app can afford: Cloudflare's clearance
     * is a domain cookie, since the image hosts of a site check it too; every other cookie is
     * bound to the very host it was read from, as a login would be. The real expiry is hidden
     * as well, so they are kept for thirty days: a session the site has ended by then is simply
     * refused, and a preference can be set again. A URL outside [site] contributes nothing.
     */
    fun cookiesFor(site: String, vararg urls: String): List<Cookie> {
        val found = LinkedHashMap<String, Cookie>()
        val expires = System.currentTimeMillis() + KEEP_DAYS * 24 * 60 * 60 * 1000
        // A site may scope its pass to the challenged path; asking for each page gathers it too.
        for (url in urls) {
            val httpUrl = url.toHttpUrlOrNull() ?: continue
            val host = httpUrl.host.lowercase()
            if (SecureCookieJar.siteOf(host) != site) continue
            val header = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull() ?: continue
            header.split(';').forEach { part ->
                val name = part.substringBefore('=').trim()
                val value = part.substringAfter('=', "").trim()
                if (name.isEmpty()) return@forEach
                runCatching {
                    Cookie.Builder().name(name).value(value).path("/").expiresAt(expires).secure()
                        .apply { if (name == CLEARANCE_COOKIE) domain(site) else hostOnlyDomain(host) }
                        .build()
                }.getOrNull()?.let { found["$name|${it.domain}"] = it }
            }
        }
        return found.values.toList()
    }

    const val CLEARANCE_COOKIE = "cf_clearance"
    private const val KEEP_DAYS = 30L

    /**
     * Deletes the WebView's directories from disk. Only safe before a WebView is created in this
     * process; run at start-up, it clears whatever a crash left behind mid-session.
     */
    fun wipeLeftovers(context: Context) {
        listOf(
            java.io.File(context.dataDir, "app_webview"),
            java.io.File(context.dataDir, "app_textures"),
            java.io.File(context.cacheDir, "WebView"),
        ).forEach { it.deleteRecursively() }
    }

    /** Leaves the WebView's storage as empty as the platform allows. */
    fun wipe(context: Context, webView: WebView?) {
        runEach(
            { webView?.clearCache(true) },
            { webView?.clearHistory() },
            { webView?.clearFormData() },
            {
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
            },
            { WebStorage.getInstance().deleteAllData() },
            { WebViewDatabase.getInstance(context).clearHttpAuthUsernamePassword() },
        )
    }
}

/** Runs every step, each on its own: one that throws keeps none of the others from running. */
internal fun runEach(vararg steps: () -> Unit) = steps.forEach { runCatching(it) }
