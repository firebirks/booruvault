package app.booruvault.net

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewDatabase
import app.booruvault.booru.BooruEngine
import app.booruvault.booru.BooruSource
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** The WebView side of anti-bot checks. The WebView keeps nothing once a check is over. */
object Challenge {
    /**
     * A WebView User-Agent in Chrome's reduced form: the real Chrome version, but "Android 10; K"
     * instead of the phone model and Android version, as Chrome itself now sends. It is also what
     * the app presents to a site after its check is passed, so it should say as little as possible.
     * e621 asks API clients to identify themselves, so for it the app's name rides along at the end.
     */
    fun userAgent(context: Context, source: BooruSource?): String {
        val default = runCatching { WebSettings.getDefaultUserAgent(context) }.getOrDefault("")
        val chrome = Regex("Chrome/(\\d+)").find(default)?.groupValues?.get(1) ?: "130"
        val browser = "Mozilla/5.0 (Linux; Android 10; K; wv) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Version/4.0 Chrome/$chrome.0.0.0 Mobile Safari/537.36"
        return if (source?.engine == BooruEngine.E621) {
            "$browser BooruVault/0.1 (by ${source.login.trim().ifBlank { "anonymous" }} on e621)"
        } else {
            browser
        }
    }

    fun hasClearance(url: String): Boolean =
        runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()?.contains("cf_clearance=") == true

    /**
     * The cookies the WebView holds for [url], as domain cookies for the whole site. CookieManager
     * hides their real expiry, so they are kept for six months: a session or clearance the site
     * has expired by then is simply refused, and a preference stays as long as the user expects.
     */
    fun cookiesFor(url: String): List<Cookie> {
        val httpUrl = url.toHttpUrlOrNull() ?: return emptyList()
        val header = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull() ?: return emptyList()
        val site = SecureCookieJar.siteOf(httpUrl.host)
        val expires = System.currentTimeMillis() + 180L * 24 * 60 * 60 * 1000
        return header.split(';').mapNotNull { part ->
            val name = part.substringBefore('=').trim()
            val value = part.substringAfter('=', "").trim()
            if (name.isEmpty()) return@mapNotNull null
            runCatching { Cookie.Builder().name(name).value(value).domain(site).path("/").expiresAt(expires).secure().build() }.getOrNull()
        }
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
