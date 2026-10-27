package io.github.firebirks.booruvault.net

import android.content.Context
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import io.github.firebirks.booruvault.booru.BooruChallengeException
import io.github.firebirks.booruvault.booru.HtmlFetcher
import io.github.firebirks.booruvault.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Reads a page in an off-screen WebView, for sites that keep answering the app's HTTP client
 * with an anti-bot check after the user passed it (Cloudflare tells a real browser from it, all
 * the more over Tor). Used only when the user turns it on: the site's own scripts run for every
 * page read this way.
 *
 * The WebView gets the same hardening as the visible one, and loads no images. The site's kept
 * cookies are handed to it for the visit; afterwards a renewed clearance is kept and everything
 * else the WebView stored is erased.
 */
class HiddenBrowser(
    private val context: Context,
    private val tor: TorManager,
    private val cookies: SecureCookieJar,
    private val settings: SettingsRepository,
    private val json: Json,
) : HtmlFetcher {
    private val mutex = Mutex()

    override suspend fun fetch(url: String): String = mutex.withLock {
        withContext(Dispatchers.Main) {
            val httpUrl = url.toHttpUrlOrNull() ?: throw BooruChallengeException(url)
            // With Tor on, a WebView that cannot be routed through it must not load anything.
            WebViewRoute.apply(tor).getOrElse { throw BooruChallengeException(url) }
            val origin = "${httpUrl.scheme}://${httpUrl.host}/"
            val agent = cookies.userAgentFor(httpUrl.host) ?: Challenge.userAgent(context, null)
            val manager = CookieManager.getInstance()
            cookies.browserCookies(httpUrl).forEach { manager.setCookie(origin, it) }
            val webView = PrivateWebView(context.applicationContext)
            try {
                withTimeout(TIMEOUT_MS) { load(webView, url, agent) }
            } catch (e: TimeoutCancellationException) {
                // Still a check after the wait: it needs a person, in the visible browser.
                throw BooruChallengeException(url)
            } finally {
                cookies.renewFromBrowser(httpUrl.host, Challenge.cookiesFor(origin, url))
                Challenge.wipe(context, webView)
                webView.destroy()
            }
        }
    }

    private suspend fun load(webView: WebView, url: String, agent: String): String = suspendCancellableCoroutine { cont ->
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            userAgentString = agent
            allowFileAccess = false
            allowContentAccess = false
            blockNetworkImage = true
            loadsImagesAutomatically = false
            safeBrowsingEnabled = settings.settings.value.webSafeBrowsing
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(webView, DISABLE_WEBRTC_SCRIPT, setOf("*"))
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, finishedUrl: String?) {
                view.evaluateJavascript("document.documentElement.outerHTML") { result ->
                    val html = runCatching { json.decodeFromString(String.serializer(), result) }.getOrNull() ?: return@evaluateJavascript
                    // A check reloads the page by itself once passed; wait for that load.
                    if (looksLikeChallenge(html)) return@evaluateJavascript
                    if (cont.isActive) cont.resume(html)
                }
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail?): Boolean {
                if (cont.isActive) cont.resumeWithException(IOException("WebView renderer gone"))
                return true
            }
        }
        webView.loadUrl(url)
    }

    private fun looksLikeChallenge(html: String): Boolean =
        html.contains("challenge-platform", ignoreCase = true) ||
            Regex("<title>[^<]*(just a moment|attention required|captcha)", RegexOption.IGNORE_CASE).containsMatchIn(html)

    private companion object {
        const val TIMEOUT_MS = 30_000L
    }
}
