package io.github.firebirks.booruvault.net

import android.net.Uri
import io.github.firebirks.booruvault.booru.BooruChallengeException
import io.github.firebirks.booruvault.booru.HtmlFetcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebRequestError
import java.io.IOException

/**
 * Reads a page in a session of the engine that is on no screen, for sites that keep answering
 * the app's HTTP client with an anti-bot check after the user passed it (Cloudflare tells a real
 * browser from it, all the more over Tor). Used only when the user turns it on: the site's own
 * scripts run for every page read this way.
 *
 * The session is a private one of [GeckoEngine], as the built-in browser's is, under the engine's
 * own name, and loads no images. The site's kept cookies are handed to it for the visit, out of
 * the page's scripts' reach; afterwards what the visit renewed of them, and a fresh clearance if
 * the visit got past the check, are kept, and everything else the session held is gone with it.
 */
class HiddenBrowser(
    private val gecko: GeckoEngine,
    private val tor: TorManager,
    private val cookies: SecureCookieJar,
) : HtmlFetcher {
    private val mutex = Mutex()

    override suspend fun fetch(url: String): String = mutex.withLock {
        // Private sessions share one cookie store, and it is the built-in browser's alone while
        // the user is at a check in it: this one waits for it to close, and gives way should it
        // open meanwhile.
        Challenge.visibleOpen.first { !it }
        withContext(Dispatchers.Main) {
            val httpUrl = url.toHttpUrlOrNull() ?: throw BooruChallengeException(url)
            // With Tor on, an engine that cannot be routed through it must not load anything.
            val route = gecko.applyRoute(tor, httpUrl.host).getOrElse { throw BooruChallengeException(url) }
            val origin = "${httpUrl.scheme}://${httpUrl.host}/"
            val site = SecureCookieJar.siteOf(httpUrl.host)
            var session: GeckoSession? = null
            var yielded = false
            // Whether the visit settled on a page of the site's own, past any check.
            var settled = false
            try {
                withTimeout(TIMEOUT_MS) {
                    coroutineScope {
                        // The visit carries this route's cookies: once the switch is flipped it
                        // stops, before the next browser to open sends it the other way. Through
                        // Tor it stops as well once the exit's verdict is withdrawn: what answers
                        // on Orbot's port then has not been checked.
                        val switched = launch {
                            tor.state.first { Route.of(it.enabled) != route || (route == Route.TOR && it.verifiedExit == null) }
                            throw BooruChallengeException(url)
                        }
                        // The built-in browser opened: the store is its now, and the visit ends.
                        val taken = launch {
                            Challenge.visibleOpen.first { it }
                            yielded = true
                            throw BooruChallengeException(url)
                        }
                        try {
                            gecko.loadImages(false)
                            // The private store is there while a private session is: the session
                            // first, then the cookies it starts with, then the page.
                            val opened = gecko.newSession().also { session = it }
                            // Nothing an earlier session left in the store goes on into this one.
                            gecko.clearPrivateCookies()
                            gecko.setCookies(origin, cookies.keptCookies(route, httpUrl))
                            load(opened, url, site, httpUrl.scheme).also { settled = true }
                        } finally {
                            switched.cancel()
                            taken.cancel()
                        }
                    }
                }
            } catch (e: TimeoutCancellationException) {
                // Still a check after the wait: it needs a person, in the visible browser.
                throw BooruChallengeException(url)
            } finally {
                withContext(NonCancellable) {
                    // Given way, the store holds the built-in browser's session: neither read nor
                    // wiped here. That browser wipes it as it closes, as always.
                    if (!yielded && session != null && tor.route == route) {
                        runCatching { gecko.cookiesFor(site, origin, url) }
                            .onSuccess { cookies.renewFromBrowser(route, httpUrl.host, GeckoSession.getDefaultUserAgent(), it, settled) }
                    }
                    session?.close()
                    if (!yielded) gecko.wipe()
                    gecko.loadImages(true)
                }
            }
        }
    }

    /**
     * Loads [url] in [session] and returns the HTML of the page it settles on. A check reloads
     * the page by itself once passed, so a page that still looks like one is waited out, until
     * the caller's patience ends.
     */
    private suspend fun load(session: GeckoSession, url: String, site: String, startScheme: String): String = coroutineScope {
        val pages = gecko.pagesOf(session)
        val stops = Channel<Unit>(Channel.CONFLATED)
        val failed = CompletableDeferred<Nothing>()
        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            // The page may try to send the visit elsewhere; the read stays on the site, whatever
            // the method, and nothing opens a window of its own.
            override fun onLoadRequest(session: GeckoSession, load: GeckoSession.NavigationDelegate.LoadRequest): GeckoResult<AllowOrDeny> =
                if (load.target == GeckoSession.NavigationDelegate.TARGET_WINDOW_CURRENT && Challenge.allowedNavigation(site, startScheme, Uri.parse(load.uri))) {
                    GeckoResult.allow()
                } else {
                    GeckoResult.deny()
                }

            override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? = null

            override fun onLoadError(session: GeckoSession, uri: String?, error: WebRequestError): GeckoResult<String>? {
                failed.completeExceptionally(IOException("the page did not load (${error.category}/${error.code})"))
                return null
            }
        }
        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStop(session: GeckoSession, success: Boolean) {
                stops.trySend(Unit)
            }
        }
        session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onCrash(session: GeckoSession) {
                failed.completeExceptionally(IOException("the engine's page process is gone"))
            }

            override fun onKill(session: GeckoSession) {
                failed.completeExceptionally(IOException("the engine's page process is gone"))
            }
        }
        // On no screen, but a page the engine takes for a background one is slowed down.
        session.setActive(true)
        session.loadUri(url)
        val watch = launch { failed.await() }
        try {
            settled(pages, stops, url, site)
        } finally {
            watch.cancel()
        }
    }

    /** The HTML of the first finished page of [site] that is not a check's. */
    private suspend fun settled(pages: GeckoEngine.PageReader, stops: ReceiveChannel<Unit>, url: String, site: String): String {
        while (true) {
            // Asked when a load ends, and now and then besides: a check may swap the page
            // without a load the engine tells of.
            withTimeoutOrNull(ASK_AGAIN_MS) { stops.receive() }
            val page = pages.read() ?: continue
            if (page.tooLarge) throw IOException("the page is larger than the app reads")
            if (!page.complete) continue
            val host = page.url.toHttpUrlOrNull()?.host
            if (host == null || SecureCookieJar.siteOf(host) != site) throw BooruChallengeException(url)
            // Off the main thread, where the session lives: a page may be megabytes long.
            if (!withContext(Dispatchers.Default) { looksLikeChallenge(page.html) }) return page.html
        }
    }

    private companion object {
        const val TIMEOUT_MS = 30_000L
        const val ASK_AGAIN_MS = 1_500L
    }
}

/** Whether [html] is an anti-bot check's page rather than the site's own. */
internal fun looksLikeChallenge(html: String): Boolean =
    html.contains("challenge-platform", ignoreCase = true) ||
        Regex("<title>[^<]*(just a moment|attention required|captcha)", RegexOption.IGNORE_CASE).containsMatchIn(html)
