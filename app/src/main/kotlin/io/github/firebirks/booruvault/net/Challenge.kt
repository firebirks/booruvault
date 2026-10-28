package io.github.firebirks.booruvault.net

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewDatabase
import io.github.firebirks.booruvault.booru.BooruSource
import io.github.firebirks.booruvault.booru.UserAgents
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

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

    fun hasClearance(url: String): Boolean =
        runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()?.contains("cf_clearance=") == true

    /**
     * The cookies the WebView holds for [url], as domain cookies for the whole site. CookieManager
     * hides their real expiry, so they are kept for six months: a session or clearance the site
     * has expired by then is simply refused, and a preference stays as long as the user expects.
     */
    fun cookiesFor(vararg urls: String): List<Cookie> {
        val found = LinkedHashMap<String, Cookie>()
        val expires = System.currentTimeMillis() + 180L * 24 * 60 * 60 * 1000
        // A site may scope its pass to the challenged path; asking for each page gathers it too.
        for (url in urls) {
            val httpUrl = url.toHttpUrlOrNull() ?: continue
            val header = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull() ?: continue
            val site = SecureCookieJar.siteOf(httpUrl.host)
            header.split(';').forEach { part ->
                val name = part.substringBefore('=').trim()
                val value = part.substringAfter('=', "").trim()
                if (name.isEmpty()) return@forEach
                runCatching { Cookie.Builder().name(name).value(value).domain(site).path("/").expiresAt(expires).secure().build() }
                    .getOrNull()?.let { found[name] = it }
            }
        }
        return found.values.toList()
    }

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
        runCatching {
            webView?.clearCache(true)
            webView?.clearHistory()
            webView?.clearFormData()
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            WebStorage.getInstance().deleteAllData()
            WebViewDatabase.getInstance(context).clearHttpAuthUsernamePassword()
        }
    }
}
