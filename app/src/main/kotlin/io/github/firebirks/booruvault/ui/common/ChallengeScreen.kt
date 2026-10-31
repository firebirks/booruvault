package io.github.firebirks.booruvault.ui.common

import android.webkit.RenderProcessGoneDetail
import io.github.firebirks.booruvault.R
import androidx.compose.ui.res.stringResource
import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.widget.Toast
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.firebirks.booruvault.booru.BooruEngine
import io.github.firebirks.booruvault.booru.BooruSource
import io.github.firebirks.booruvault.net.Challenge
import io.github.firebirks.booruvault.net.DISABLE_WEBRTC_SCRIPT
import io.github.firebirks.booruvault.net.PrivateWebView
import io.github.firebirks.booruvault.net.Route
import io.github.firebirks.booruvault.net.SecureCookieJar
import io.github.firebirks.booruvault.net.TorUnverifiedException
import io.github.firebirks.booruvault.net.WebViewRoute
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Opens [url] in the built-in browser; [onDone] runs once the user is through. */
fun interface ChallengeLauncher {
    fun open(source: BooruSource?, url: String, mode: ChallengeMode, onDone: () -> Unit)

    /** The anti-bot check of [url]'s site; closes by itself once passed. */
    fun open(source: BooruSource?, url: String, onSolved: () -> Unit) = open(source, url, ChallengeMode.SITE, onSolved)
}

val LocalChallenge = staticCompositionLocalOf<ChallengeLauncher> { ChallengeLauncher { _, _, _, _ -> } }

enum class ChallengeMode {
    /** The anti-bot check of the site, met at its home: an API address may carry a key. Closes once passed. */
    SITE,

    /** The check guarding the address itself, such as a file host's on its files. Closes once passed. */
    ADDRESS,

    /** The address itself, for logins and site preferences, until the user taps Done. */
    BROWSE,
}

data class ChallengeRequest(val source: BooruSource?, val url: String, val mode: ChallengeMode, val onSolved: () -> Unit)

/** The page the built-in browser opens for [url]: the site's home for its check, otherwise the address itself. */
fun challengeStart(url: String, mode: ChallengeMode): String {
    if (mode != ChallengeMode.SITE) return url
    val parsed = url.toHttpUrlOrNull() ?: return url
    return "${parsed.scheme}://${parsed.host}/"
}

/**
 * How the built-in browser uses its cache for [mode]. A file host's check guards a file the vault
 * is to keep: every load of it goes to the network, never to a copy the cache holds.
 */
fun challengeCacheMode(mode: ChallengeMode): Int =
    if (mode == ChallengeMode.ADDRESS) WebSettings.LOAD_NO_CACHE else WebSettings.LOAD_DEFAULT

/**
 * Where the built-in browser starts for [source]'s settings and login: Gelbooru's options page
 * holds "Display all site content"; other sites open at their home.
 */
fun browserStart(source: BooruSource): String {
    val base = source.baseUrl.trim().trimEnd('/')
    return when (source.engine) {
        BooruEngine.GELBOORU, BooruEngine.GELBOORU_HTML ->
            base.replace("://api.", "://") + "/index.php?page=account&s=options"
        BooruEngine.HTML_GENERIC -> base.replace("{tags}", "").replace("{page}", "1")
        else -> "$base/"
    }
}

/**
 * The site in a real browser, so the user can pass its check by hand. The clearance cookie lands
 * in the WebView cookie store the app's HTTP client reads from; once it appears, this closes.
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChallengeScreen(request: ChallengeRequest, onClose: (solved: Boolean) -> Unit) {
    val context = LocalContext.current
    val container = LocalContainer.current
    val cookies = container.cookies
    val appSettings by container.settings.settings.collectAsStateWithLifecycle()
    val safeBrowsing = appSettings.webSafeBrowsing
    var webView by remember { mutableStateOf<WebView?>(null) }
    // Null while the proxy is being applied; a message if the browser must not open at all.
    var route by remember { mutableStateOf<Result<Route>?>(null) }
    LaunchedEffect(Unit) { route = WebViewRoute.apply(container.tor) }
    val browse = request.mode == ChallengeMode.BROWSE
    val exact = request.mode == ChallengeMode.ADDRESS
    val origin = remember(request.url) {
        val uri = Uri.parse(request.url)
        "${uri.scheme}://${uri.host}/"
    }
    val target = remember(request.url, request.mode) { challengeStart(request.url, request.mode) }
    val host = remember(origin) { Uri.parse(origin).host.orEmpty() }
    // The session stays on this site; the bar names the host actually on screen.
    val site = remember(host) { SecureCookieJar.siteOf(host) }
    val startScheme = remember(origin) { Uri.parse(origin).scheme.orEmpty().lowercase() }
    var currentHost by remember { mutableStateOf(host) }
    val userAgent = remember(request.source) { Challenge.userAgent(context, request.source) }
    var progress by remember { mutableIntStateOf(0) }
    var title by remember { mutableStateOf("") }
    val blocked = stringResource(R.string.browser_blocked_outside, site)

    // Several paths lead out (the check passed, a tap, Back); only the first one counts.
    val closed = remember { AtomicBoolean(false) }

    fun finish(solved: Boolean, record: Boolean = solved) {
        if (!closed.compareAndSet(false, true)) return
        // What the page earned belongs to the route it loaded along, and only while that holds.
        val loadedAlong = route?.getOrNull()
        if (record && loadedAlong != null && loadedAlong == container.tor.route) {
            cookies.recordClearance(loadedAlong, host, userAgent, Challenge.cookiesFor(site, origin, request.url, webView?.url ?: origin))
        }
        // The WebView's own stores are in clear; once the clearance is sealed, they are emptied.
        Challenge.wipe(context, webView)
        onClose(solved)
    }

    BackHandler { finish(Challenge.hasClearance(target)) }

    // The page title stays "Just a moment..." until the check is through.
    if (!browse && route?.isSuccess == true) LaunchedEffect(target) {
        while (true) {
            delay(1000)
            if (Challenge.hasClearance(target) && progress == 100 && !title.contains("moment", ignoreCase = true)) {
                finish(true)
                break
            }
        }
    }

    Surface(Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(stringResource(if (browse) R.string.browser_title else R.string.challenge_title))
                            Text(currentHost, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    navigationIcon = { IconButton(onClick = { finish(false) }) { Icon(Icons.Outlined.Close, stringResource(R.string.action_close)) } },
                    actions = { TextButton(onClick = { finish(true) }) { Text(stringResource(R.string.action_done)) } },
                )
            },
            contentWindowInsets = WindowInsets(0),
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                Text(
                    stringResource(if (browse) R.string.browser_hint else R.string.challenge_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                val ready = route
                if (ready == null) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    return@Column
                }
                ready.exceptionOrNull()?.let { failure ->
                    Text(
                        stringResource(if (failure is TorUnverifiedException) R.string.browser_tor_unverified else R.string.browser_tor_unsupported),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(16.dp),
                    )
                    return@Column
                }
                if (progress < 100) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
                AndroidView(
                    factory = { ctx ->
                        PrivateWebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.userAgentString = userAgent
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            settings.safeBrowsingEnabled = safeBrowsing
                            settings.cacheMode = challengeCacheMode(request.mode)
                            CookieManager.getInstance().setAcceptCookie(true)
                            // Third-party cookies are how ad networks follow a visit; Cloudflare's
                            // check works without them.
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                                WebViewCompat.addDocumentStartJavaScript(this, DISABLE_WEBRTC_SCRIPT, setOf("*"))
                            }
                            webViewClient = object : WebViewClient() {
                                // Links and scripts may point anywhere; only the site itself is followed.
                                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                    if (!request.isForMainFrame || Challenge.allowedNavigation(site, startScheme, request.url)) return false
                                    Toast.makeText(context, blocked, Toast.LENGTH_SHORT).show()
                                    return true
                                }

                                // A guarded file is never fetched here: once its check is passed, the request
                                // that would bring it is answered empty and the browser closes. The downloads
                                // then fetch it themselves.
                                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                                    val url = request.url.toString()
                                    if (!exact || !Challenge.answersEmpty(target, url) { Challenge.hasClearance(url) }) return null
                                    view.post { finish(true) }
                                    return Challenge.emptyResponse()
                                }

                                // The host let the browser through without a check, and sent nothing of the file
                                // for the range it was asked: there is nothing to pass here. The downloads try
                                // again and say what they meet.
                                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                                    if (exact && request.isForMainFrame && errorResponse.statusCode == 416 && Challenge.isGuardedFile(target, request.url.toString())) {
                                        finish(true, record = false)
                                    }
                                }

                                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                                    progress = 0
                                    url?.let { Uri.parse(it).host }?.let { currentHost = it }
                                }

                                override fun onPageFinished(view: WebView, url: String?) {
                                    title = view.title.orEmpty()
                                }

                                // A heavy page can take the renderer down; close the browser rather than the app.
                                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail?): Boolean {
                                    finish(false)
                                    return true
                                }
                            }
                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView, newProgress: Int) {
                                    progress = newProgress
                                }

                                override fun onReceivedTitle(view: WebView, newTitle: String?) {
                                    title = newTitle.orEmpty()
                                }
                            }
                            if (exact) loadUrl(target, mapOf("Range" to Challenge.NO_BYTES_RANGE)) else loadUrl(target)
                            webView = this
                        }
                    },
                    onRelease = {
                        it.stopLoading()
                        Challenge.wipe(context, it)
                        it.destroy()
                        webView = null
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
