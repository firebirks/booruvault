package io.github.firebirks.booruvault.ui.common

import io.github.firebirks.booruvault.R
import androidx.compose.ui.res.stringResource
import android.net.Uri
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.firebirks.booruvault.booru.BooruEngine
import io.github.firebirks.booruvault.booru.BooruSource
import io.github.firebirks.booruvault.net.Challenge
import io.github.firebirks.booruvault.net.Route
import io.github.firebirks.booruvault.net.SecureCookieJar
import io.github.firebirks.booruvault.net.TorUnverifiedException
import io.github.firebirks.booruvault.net.OnionWithoutTorException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView

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
 * A load of the guarded file at [uri] that brings none of it. A file host's check guards a file
 * the vault is to keep: it is asked for with a range no file has, and from the network, never
 * from a copy a cache holds.
 */
private fun guardedLoad(uri: String): GeckoSession.Loader = GeckoSession.Loader().uri(uri)
    .additionalHeaders(mapOf("Range" to Challenge.NO_BYTES_RANGE))
    .headerFilter(GeckoSession.HEADER_FILTER_UNRESTRICTED_UNSAFE)
    .flags(GeckoSession.LOAD_FLAGS_BYPASS_CACHE)

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
 * The site in a real browser, so the user can pass its check by hand. The browser is a private
 * session of GeckoView, where WebRTC is not there to go around the proxy; once the session holds
 * the clearance cookie, which the app's HTTP client then sends, this closes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChallengeScreen(request: ChallengeRequest, onClose: (solved: Boolean) -> Unit) {
    val container = LocalContainer.current
    val cookies = container.cookies
    val gecko = container.gecko
    var session by remember { mutableStateOf<GeckoSession?>(null) }
    // Null while the proxy is being set; a message if the browser must not open at all.
    var route by remember { mutableStateOf<Result<Route>?>(null) }
    LaunchedEffect(Unit) {
        val applied = gecko.applyRoute(container.tor, Uri.parse(request.url).host)
        // The hidden browser reads pages without their images, and gives way to this one.
        gecko.loadImages(true)
        route = applied
    }
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
    var currentUrl by remember { mutableStateOf(origin) }
    var canGoBack by remember { mutableStateOf(false) }
    // The engine's own name: a pass is bound to the name that earned it, and anti-bot checks tell
    // a name the engine does not match.
    val userAgent = remember { GeckoSession.getDefaultUserAgent() }
    var progress by remember { mutableIntStateOf(0) }
    val blocked = stringResource(R.string.browser_blocked_outside, site)
    // In the browser's own window, behind its screen protection: a toast is the system's, and
    // would show the site's name in a screenshot or a recording.
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Several paths lead out (the check passed, a tap, Back); only the first one counts.
    val closed = remember { AtomicBoolean(false) }
    // The private sessions' cookie store is this browser's alone while it is up: see
    // Challenge.visibleOpen. It is given back once the session is closed and wiped, on whichever
    // way out, so that the hidden browser's next visit does not open while they run.
    DisposableEffect(Unit) {
        Challenge.visibleOpened()
        onDispose { }
    }

    // Whether the site's last page was the check's own, as Cloudflare marks it, or null while no
    // page has answered. A clearance cookie alone does not say the check is passed: Cloudflare
    // sets one while a check is still on, and takes it back by asking again. And the page's
    // title, "Just a moment...", comes in the phone's language.
    var checkOn by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) {
        gecko.statuses.collect { answered ->
            val answeredBy = Uri.parse(answered.url).host?.lowercase() ?: return@collect
            if (SecureCookieJar.siteOf(answeredBy) == site) checkOn = answered.challenge
        }
    }

    // Whether the session holds a clearance, or null when the engine's extension did not say.
    suspend fun clearance(url: String): Boolean? =
        runCatching { gecko.cookiesFor(site, url).any { it.name == Challenge.CLEARANCE_COOKIE } }.getOrNull()

    suspend fun cleared(url: String): Boolean = clearance(url) == true

    fun finish(solved: Boolean, record: Boolean = solved) {
        if (!closed.compareAndSet(false, true)) return
        // What the page earned belongs to the route it loaded along, and only while that holds.
        val loadedAlong = route?.getOrNull()
        val open = session
        val last = currentUrl
        // In the app's scope: the cookies are asked of the engine, and the screen may be gone by then.
        container.scope.launch(Dispatchers.Main) {
            val routeHeld = loadedAlong != null && loadedAlong == container.tor.route
            val recorded = record && routeHeld
            try {
                if (recorded && loadedAlong != null) {
                    val earned = runCatching { gecko.cookiesFor(site, origin, request.url, last) }.getOrDefault(emptyList())
                    cookies.recordClearance(loadedAlong, host, userAgent, earned)
                    // The request that follows is to leave Tor where the browser just did.
                    container.freshConnections()
                }
                // A private session keeps nothing once closed; whatever is left is deleted all the same.
                open?.close()
                gecko.wipe()
            } finally {
                Challenge.visibleClosed()
            }
            // A visit counts as done when what it earned holds: a browser that never opened, or
            // whose route changed meanwhile, has nothing for the caller to try again with.
            onClose(if (browse) recorded else solved && routeHeld)
        }
    }

    // A visit for a login or the site's preferences is kept however it ends: a login thrown away
    // by Back is the surprise to avoid. Back first steps back through the pages, as in a browser.
    BackHandler {
        when {
            !browse -> scope.launch { finish(checkOn != true && cleared(target)) }
            canGoBack -> session?.goBack()
            else -> finish(true)
        }
    }

    // The check is through once the site has answered with a page of its own and the session
    // holds a clearance.
    if (!browse && route?.isSuccess == true) LaunchedEffect(target) {
        while (true) {
            delay(1000)
            if (progress == 100 && checkOn == false && cleared(target)) {
                finish(true)
                break
            }
        }
    }

    // The host answered the range it was asked with nothing of the file, and not with a check. If
    // the session holds a clearance, the check is passed and what it earned is kept. If it holds
    // none, the host let the browser through without a check and there is nothing to keep: the
    // downloads try again and say what they meet.
    if (exact) LaunchedEffect(Unit) {
        gecko.statuses.collect {
            if (it.status == 416 && !it.challenge && Challenge.isGuardedFile(target, it.url)) finish(true, record = cleared(target))
        }
    }

    // Through Tor the page stays only while the exit's verdict holds: once it is withdrawn, what
    // answers on Orbot's port has not been checked, and the browser closes, keeping nothing.
    if (route?.getOrNull() == Route.TOR) LaunchedEffect(Unit) {
        container.tor.state.first { !it.enabled || it.verifiedExit == null }
        session?.stop()
        finish(solved = false, record = false)
    }

    // The session opens once the route is set, and loads the page.
    LaunchedEffect(route) {
        if (route?.isSuccess != true || session != null || closed.get()) return@LaunchedEffect
        val opened = gecko.newSession()
        opened.navigationDelegate = object : GeckoSession.NavigationDelegate {
            // Links and scripts may point anywhere; only the site itself is followed, whatever
            // the method, and nothing opens a window of its own.
            override fun onLoadRequest(session: GeckoSession, load: GeckoSession.NavigationDelegate.LoadRequest): GeckoResult<AllowOrDeny> {
                val allowed = load.target == GeckoSession.NavigationDelegate.TARGET_WINDOW_CURRENT &&
                    Challenge.allowedNavigation(site, startScheme, Uri.parse(load.uri))
                if (!allowed) {
                    scope.launch { snackbar.showSnackbar(blocked) }
                    return GeckoResult.deny()
                }
                // A guarded file is never fetched here. The browser asks for it with a range no
                // file has. Once the session holds a clearance, a request of the page's own for
                // the file, which would bring it if the check is passed, is refused and made
                // again with that range. A clearance does not say the check is passed, as
                // Cloudflare sets one while it is still on: what comes back does, the check's
                // page again or no byte of the file, and the browser closes on the latter. The
                // downloads then fetch the file themselves.
                if (!exact || load.isDirectNavigation || !Challenge.isGuardedFile(target, load.uri)) return GeckoResult.allow()
                val answer = GeckoResult<AllowOrDeny>()
                scope.launch {
                    if (Challenge.refusesOwnRequest(clearance(load.uri))) {
                        answer.complete(AllowOrDeny.DENY)
                        session.load(guardedLoad(load.uri))
                    } else {
                        answer.complete(AllowOrDeny.ALLOW)
                    }
                }
                return answer
            }

            override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? = null

            // Nor is a guarded file fetched in a frame, which only the page's own request above
            // would be let through for.
            override fun onSubframeLoadRequest(session: GeckoSession, load: GeckoSession.NavigationDelegate.LoadRequest): GeckoResult<AllowOrDeny>? =
                if (exact && Challenge.isGuardedFile(target, load.uri)) GeckoResult.deny() else null

            override fun onLocationChange(
                session: GeckoSession,
                url: String?,
                perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>,
                hasUserGesture: Boolean,
            ) {
                if (url == null) return
                currentUrl = url
                // An address with no host, as a large data: document Gecko does not ask about, is
                // not the site's: the bar says what it is rather than keep the site's name.
                val shownAt = Uri.parse(url)
                currentHost = shownAt.host ?: if (url == "about:blank") currentHost else "${shownAt.scheme}:"
            }

            override fun onCanGoBack(session: GeckoSession, can: Boolean) {
                canGoBack = can
            }
        }
        opened.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                progress = 0
            }

            override fun onProgressChange(session: GeckoSession, value: Int) {
                progress = value
            }
        }
        opened.contentDelegate = object : GeckoSession.ContentDelegate {
            // A heavy page can take its process down; close the browser rather than the app. A
            // visit for a login keeps what it brought, as it does on every other way out.
            override fun onCrash(session: GeckoSession) = finish(browse)

            override fun onKill(session: GeckoSession) = finish(browse)
        }
        // The app reads none of this browser's pages, and the store starts empty of what an
        // earlier session left.
        gecko.ignorePages(opened)
        gecko.clearPrivateCookies()
        opened.load(if (exact) guardedLoad(target) else GeckoSession.Loader().uri(target))
        session = opened
    }

    // Leaving the screen any other way closes the session too, and keeps nothing.
    DisposableEffect(Unit) {
        onDispose {
            if (closed.compareAndSet(false, true)) {
                val open = session
                container.scope.launch(Dispatchers.Main) {
                    try {
                        open?.close()
                        gecko.wipe()
                    } finally {
                        Challenge.visibleClosed()
                    }
                }
            }
        }
    }

    // While the app is not on screen the page is a background one: it holds no wake lock, plays
    // nothing and is slowed down, rather than run on behind another app until the vault locks.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, session) {
        val shown = session
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> shown?.setActive(true)
                Lifecycle.Event.ON_STOP -> shown?.setActive(false)
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    Surface(Modifier.fillMaxSize()) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(stringResource(if (browse) R.string.browser_title else R.string.challenge_title))
                            Text(currentHost, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    navigationIcon = { IconButton(onClick = { finish(browse) }) { Icon(Icons.Outlined.Close, stringResource(R.string.action_close)) } },
                    actions = { TextButton(onClick = { finish(true) }) { Text(stringResource(R.string.action_done)) } },
                )
            },
            // Over the whole screen, tabs included: the page stops above the system's navigation
            // bar and the keyboard, so neither covers the end of it or the field being typed in.
            contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
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
                        stringResource(
                            when (failure) {
                                is TorUnverifiedException -> R.string.browser_tor_unverified
                                is OnionWithoutTorException -> R.string.problem_onion_needs_tor
                                // The engine could not be routed, or does not hold its preferences.
                                else -> R.string.browser_engine_refused
                            },
                        ),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(16.dp),
                    )
                    return@Column
                }
                if (progress < 100) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
                val shown = session ?: return@Column
                AndroidView(
                    factory = { ctx ->
                        GeckoView(ctx).apply {
                            // The system's autofill service is kept out; a private session already
                            // asks the keyboard not to learn from what is typed.
                            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                            // Drawn into the window, under its screen protection: a SurfaceView's
                            // layer carries a flag of its own, which GeckoView never sets, and on
                            // Android 8 and 9 a screen recording could show the page.
                            setViewBackend(GeckoView.BACKEND_TEXTURE_VIEW)
                            setSession(shown)
                        }
                    },
                    onRelease = { it.releaseSession() },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
