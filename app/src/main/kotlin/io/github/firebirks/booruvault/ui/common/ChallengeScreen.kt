package io.github.firebirks.booruvault.ui.common

import android.webkit.RenderProcessGoneDetail
import io.github.firebirks.booruvault.R
import androidx.compose.ui.res.stringResource
import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebChromeClient
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
import io.github.firebirks.booruvault.booru.BooruSource
import io.github.firebirks.booruvault.net.Challenge
import io.github.firebirks.booruvault.net.DISABLE_WEBRTC_SCRIPT
import io.github.firebirks.booruvault.net.PrivateWebView
import io.github.firebirks.booruvault.net.WebViewRoute
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.delay

/** Opens [url] in the built-in browser; [onDone] runs once the user is through. */
fun interface ChallengeLauncher {
    fun open(source: BooruSource?, url: String, browse: Boolean, onDone: () -> Unit)

    /** The anti-bot check of [url]'s site; closes by itself once passed. */
    fun open(source: BooruSource?, url: String, onSolved: () -> Unit) = open(source, url, false, onSolved)
}

val LocalChallenge = staticCompositionLocalOf<ChallengeLauncher> { ChallengeLauncher { _, _, _, _ -> } }

/**
 * [browse] opens [url] itself and waits for "Fatto", for logins and site preferences; otherwise
 * the site's home is opened for its anti-bot check, which closes on its own once passed.
 */
data class ChallengeRequest(val source: BooruSource?, val url: String, val browse: Boolean, val onSolved: () -> Unit)

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
    var route by remember { mutableStateOf<Result<Unit>?>(null) }
    LaunchedEffect(Unit) { route = WebViewRoute.apply(container.tor) }
    val origin = remember(request.url) {
        val uri = Uri.parse(request.url)
        "${uri.scheme}://${uri.host}/"
    }
    val target = if (request.browse) request.url else origin
    val host = remember(origin) { Uri.parse(origin).host.orEmpty() }
    val userAgent = remember(request.source) { Challenge.userAgent(context, request.source) }
    var progress by remember { mutableIntStateOf(0) }
    var title by remember { mutableStateOf("") }

    fun finish(solved: Boolean) {
        if (solved) cookies.recordClearance(host, userAgent, Challenge.cookiesFor(origin, request.url, webView?.url ?: origin))
        // The WebView's own stores are in clear; once the clearance is sealed, they are emptied.
        Challenge.wipe(context, webView)
        onClose(solved)
    }

    BackHandler { finish(Challenge.hasClearance(target)) }

    // The page title stays "Just a moment..." until the check is through.
    if (!request.browse && route?.isSuccess == true) LaunchedEffect(target) {
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
                            Text(stringResource(if (request.browse) R.string.browser_title else R.string.challenge_title))
                            Text(host, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    stringResource(if (request.browse) R.string.browser_hint else R.string.challenge_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                val ready = route
                if (ready == null) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    return@Column
                }
                ready.exceptionOrNull()?.let {
                    Text(
                        stringResource(R.string.browser_tor_unsupported),
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
                            CookieManager.getInstance().setAcceptCookie(true)
                            // Third-party cookies are how ad networks follow a visit; Cloudflare's
                            // check works without them.
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                                WebViewCompat.addDocumentStartJavaScript(this, DISABLE_WEBRTC_SCRIPT, setOf("*"))
                            }
                            webViewClient = object : WebViewClient() {
                                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                                    progress = 0
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
                            loadUrl(target)
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
