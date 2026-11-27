package io.github.firebirks.booruvault.net

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import io.github.firebirks.booruvault.booru.UserAgents
import io.github.firebirks.booruvault.booru.await
import io.github.firebirks.booruvault.booru.utf8UpTo
import io.github.firebirks.booruvault.util.HasProblem
import io.github.firebirks.booruvault.util.Problem
import io.github.firebirks.booruvault.util.toProblem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.boolean
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLException

enum class TorStatus { DISABLED, NOT_INSTALLED, CHECKING, ON, OFF }

data class TorState(
    val enabled: Boolean,
    val status: TorStatus,
    /** The port the app connects to: a setting. */
    val socksPort: Int = DEFAULT_SOCKS_PORT,
    /** Result of the last check against check.torproject.org: exit address, or null if unchecked. */
    val verifiedExit: String? = null,
    val verifyError: Problem? = null,
    /** Counts the route changes whose old connections have been dropped; see [TorManager.routeSettled]. */
    val routeChanges: Int = 0,
) {
    /** Whether nothing can go out right now: Tor is on and its exit is not verified, whatever the reason. */
    val blocking: Boolean get() = enabled && verifiedExit == null
}

const val DEFAULT_SOCKS_PORT = 9050

/**
 * Routes the app through Tor via Orbot's SOCKS proxy.
 *
 * Fail-closed: while enabled, the app's client has Orbot's port as its only proxy, with no direct
 * fallback (see [RoutedClient]), so if Orbot is not running every connection fails instead of
 * going out in clear.
 * Host names reach the proxy unresolved, so DNS goes through Tor as well.
 *
 * The on/off flag and the port are the settings kept outside the vault (`network.xml`): they
 * must hold from process start, before any unlock, so that no connection can ever slip out
 * directly. They reveal only that the app is set to use Tor, and where.
 *
 * Orbot is neither started nor asked anything: since Orbot 17.9.6 other apps cannot start it,
 * and its status broadcasts, which any app could forge, are gone with that. Whether it listens
 * is found out by greeting the port as a SOCKS5 client. A SOCKS5 greeting proves nothing about
 * who answers, though: any local process that took the port first would do, see the host names
 * in every CONNECT and go out in clear. So whether traffic really leaves through Tor is checked
 * against the Tor Project's exit test as soon as the port answers, and again whenever it stops
 * and comes back: a probe that finds it silent, or a connection the listener itself refuses
 * ([listenerLost]), forgets the verdict, and whatever answers next has to pass the check anew.
 * Until the check passes, [TorExitGate] lets nothing but the check itself out, and
 * [TorRouteGuard] refuses each attempt that reached the port meanwhile.
 */
class TorManager(private val context: Context, private val scope: CoroutineScope, hasVault: Boolean) {
    private val prefs = context.getSharedPreferences("network", Context.MODE_PRIVATE)

    private val _routeChosen = MutableStateFlow(
        routeChosenAtStart(if (prefs.contains(KEY_ROUTE_CHOSEN)) prefs.getBoolean(KEY_ROUTE_CHOSEN, false) else null, hasVault).also {
            // Written at once, so a vault created and closed before the question is still asked it.
            if (!prefs.contains(KEY_ROUTE_CHOSEN)) prefs.edit().putBoolean(KEY_ROUTE_CHOSEN, it).commit()
        },
    )

    /**
     * Whether the user has said how the app reaches sites: through Tor or directly. A fresh install
     * asks right after the vault is created, and until the answer [RouteChoiceGate] lets nothing out.
     */
    val routeChosen: StateFlow<Boolean> = _routeChosen.asStateFlow()

    /** Records the answer to the first start's question; Settings can change the route afterwards. */
    fun chooseRoute(useTor: Boolean) {
        // The route is set before the gate opens, so nothing can leave by the other one; Orbot is
        // greeted, and its exit checked, once the gate lets the check through.
        if (useTor != enabled) setEnabled(useTor, refresh = false)
        prefs.edit().putBoolean(KEY_ROUTE_CHOSEN, true).commit()
        _routeChosen.value = true
        refreshStatus()
    }

    private val _state = MutableStateFlow(
        prefs.getBoolean(KEY_ENABLED, false).let { enabled ->
            TorState(
                enabled,
                if (enabled) TorStatus.CHECKING else TorStatus.DISABLED,
                socksPort = prefs.getInt(KEY_PORT, DEFAULT_SOCKS_PORT).takeIf { it in 1..65535 } ?: DEFAULT_SOCKS_PORT,
            )
        },
    )
    val state: StateFlow<TorState> = _state.asStateFlow()

    val enabled: Boolean get() = _state.value.enabled

    /** The route new connections take. */
    val route: Route get() = Route.of(enabled)

    /** [client] made along the route in force at each call: see [RoutedClient]. */
    fun routed(client: OkHttpClient): RoutedClient = RoutedClient(client, { enabled }, { _state.value.socksPort })

    val dns: okhttp3.Dns = TorAwareDns { enabled }

    /** Called when the route changes, so pooled connections opened the other way are dropped. */
    var onRouteChanged: () -> Unit = {}

    /**
     * Said by [onRouteChanged]'s work once the connections of the old route are cancelled and
     * dropped: a request made before that could be cancelled along with them, so what waits to
     * try the new route again waits for this.
     */
    fun routeSettled() = _state.update { it.copy(routeChanges = it.routeChanges + 1) }

    /** Called when Orbot answers and the exit has not been verified: runs [verify] with the app's client. */
    var onVerifyNeeded: () -> Unit = {}

    /** True once the exit check passed on the current port and Orbot has answered ever since. */
    val verified: Boolean get() = _state.value.verifiedExit != null

    private val verifying = AtomicBoolean(false)
    private val probing = AtomicBoolean(false)

    /**
     * Counts the times the listener was found gone. A check that began before the last of them
     * went through a listener that is no more: whatever it says, it is not about the one there now.
     */
    private val listenerLosses = AtomicLong()

    /** Counts the probes of the port begun, and names the last one over: see [awaitVerdict]. */
    private val probesBegun = AtomicLong()

    @Volatile
    private var lastProbeOver = 0L

    @Volatile
    private var lastVerifyAt = 0L

    fun isOrbotInstalled(): Boolean = try {
        context.packageManager.getPackageInfo(ORBOT_PACKAGE, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    fun setEnabled(value: Boolean, refresh: Boolean = true) {
        prefs.edit().putBoolean(KEY_ENABLED, value).commit()
        // The port and the count of settled route changes are kept: the count going back would
        // read as a change settled, before this one's connections are dropped.
        _state.update { TorState(value, if (value) TorStatus.CHECKING else TorStatus.DISABLED, socksPort = it.socksPort, routeChanges = it.routeChanges) }
        onRouteChanged()
        if (value && refresh) refreshStatus()
    }

    /** Sets the local port the app connects to Orbot on. Returns false for an invalid port. */
    fun setSocksPort(port: Int): Boolean {
        if (port !in 1..65535) return false
        if (port == _state.value.socksPort) return true
        prefs.edit().putInt(KEY_PORT, port).commit()
        _state.update { it.copy(socksPort = port, verifiedExit = null, verifyError = null) }
        onRouteChanged()
        refreshStatus()
        return true
    }

    /**
     * Finds out whether Orbot is installed and a SOCKS5 server answers on the port. A port that
     * stops answering forgets its verification: whatever answers next has to pass the check.
     * One probe at a time: a burst of refused requests asks once, not once each.
     */
    fun refreshStatus() {
        if (!enabled) return
        if (!probing.compareAndSet(false, true)) return
        val probe = probesBegun.incrementAndGet()
        scope.launch(Dispatchers.IO) {
            try {
                val status = when {
                    !isOrbotInstalled() -> TorStatus.NOT_INSTALLED
                    socks5Listening(_state.value.socksPort) -> TorStatus.ON
                    else -> TorStatus.OFF
                }
                if (status != TorStatus.ON) listenerLosses.incrementAndGet()
                _state.update {
                    when {
                        !it.enabled -> it
                        status == TorStatus.ON -> it.copy(status = status)
                        else -> it.copy(status = status, verifiedExit = null, verifyError = null)
                    }
                }
                val now = System.currentTimeMillis()
                if (checkDue(_state.value, verifying.get(), now, lastVerifyAt)) {
                    lastVerifyAt = now
                    // What the last check said goes as this one is asked for, not when it starts:
                    // whoever waits on this probe must not read the old failure as the answer.
                    _state.update { if (it.enabled) it.copy(verifyError = null) else it }
                    onVerifyNeeded()
                }
                lastProbeOver = probe
            } finally {
                probing.set(false)
            }
        }
    }

    /**
     * A connection to the port failed in a way that blames the listener, not Tor: nothing
     * accepted, or something that does not speak SOCKS5 did. Orbot is gone, or replaced, so its
     * verdict is forgotten and the port is probed again: what answers next is unproven until the
     * exit check passes through it. A failure Tor itself reports, such as a host it cannot reach,
     * says nothing about the listener and keeps the verdict; see [socksListenerLost].
     */
    fun listenerLost() {
        if (!enabled) return
        listenerLosses.incrementAndGet()
        _state.update { if (it.verifiedExit == null) it else it.copy(verifiedExit = null) }
        refreshStatus()
    }

    /**
     * The app is in front again after [awayMs] away. Orbot's port is looked at again, and after
     * [RECHECK_AFTER_MS] away the exit is checked again as well: a process that took the port
     * while the app was not using it, with no moment the app saw it silent, answers as Orbot did,
     * and only the check can tell them apart. Until it passes nothing leaves, as at start.
     */
    fun cameBack(awayMs: Long) {
        if (!enabled) return
        if (exitStale(awayMs)) {
            listenerLosses.incrementAndGet()
            _state.update { if (it.verifiedExit == null) it else it.copy(verifiedExit = null) }
            lastVerifyAt = 0
        }
        refreshStatus()
    }

    /**
     * Waits, for a request the exit check held back, until the check has spoken: true once the
     * exit is verified, or Tor is switched off. False when there is nothing to wait for, see
     * [verdict], and after [timeoutMs]. The port is probed again meanwhile, which runs the check
     * when one is due: a listener that answers with no check on its way would otherwise be
     * waited on for nothing.
     *
     * That there is nothing to wait for is taken only from a probe begun after the wait was:
     * what the state says before that may be from when Orbot was last looked at, and Orbot
     * started since then would be given up on while its port is about to answer.
     */
    suspend fun awaitVerdict(timeoutMs: Long = VERDICT_WAIT_MS): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        val before = probesBegun.get()
        while (true) {
            when (verdict(_state.value)) {
                true -> return true
                false -> if (lastProbeOver > before) return false
                null -> Unit
            }
            if (System.nanoTime() >= deadline) return false
            refreshStatus()
            delay(VERDICT_POLL_MS)
        }
    }

    fun orbotLaunchIntent(): Intent? = context.packageManager.getLaunchIntentForPackage(ORBOT_PACKAGE)

    /** Runs the exit check now, at the user's request, whatever the last one said and whenever it was. */
    fun verifyNow() {
        if (!enabled) return
        lastVerifyAt = System.currentTimeMillis()
        onVerifyNeeded()
    }

    /**
     * Asks the Tor Project whether requests reach it through Tor. One check at a time, and its
     * verdict belongs to the listener it went through: a port changed meanwhile, Tor switched
     * off, or the listener found gone, drops it, since what answers now has not been checked.
     */
    suspend fun verify(client: Call.Factory, json: Json) = withContext(Dispatchers.IO) {
        if (!verifying.compareAndSet(false, true)) return@withContext
        val port = _state.value.socksPort
        val losses = listenerLosses.get()
        try {
            // Switched off since the check was asked for: nothing to check, and nothing goes out.
            if (!enabled) return@withContext
            _state.update { it.copy(verifiedExit = null, verifyError = null) }
            val request = Request.Builder().url("https://$VERIFY_HOST/api/ip").header("User-Agent", UserAgents.browser).build()
            val body = client.newCall(request).await().use { it.body.utf8UpTo(MAX_VERIFY_BODY) ?: "" }
            val result = json.parseToJsonElement(body).jsonObject
            val isTor = result["IsTor"]?.jsonPrimitive?.boolean == true
            val ip = result["IP"]?.jsonPrimitive?.content
            _state.update {
                when {
                    !speaksFor(it, port, losses, listenerLosses.get()) -> it
                    isTor -> it.copy(verifiedExit = ip ?: "?")
                    else -> it.copy(verifyError = Problem(Problem.Code.NOT_THROUGH_TOR))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { if (!speaksFor(it, port, losses, listenerLosses.get())) it else it.copy(verifyError = e.toProblem()) }
        } finally {
            verifying.set(false)
        }
    }

    companion object {
        const val ORBOT_PACKAGE = "org.torproject.android"

        /**
         * Whether the route counts as chosen when the app starts: the answer once given; otherwise
         * a vault already there means the install predates the question, and is not asked it.
         */
        internal fun routeChosenAtStart(stored: Boolean?, hasVault: Boolean): Boolean = stored ?: hasVault

        /** The exit check's host: the one request allowed out before the check has passed. */
        const val VERIFY_HOST = "check.torproject.org"

        /** The least time between two automatic checks, so a check that keeps failing does not loop. */
        private const val VERIFY_INTERVAL_MS = 10_000L

        /**
         * Away this long, the exit is checked again on the way back: as long as the auto-lock's
         * default, so the check most often runs while the password is typed.
         */
        const val RECHECK_AFTER_MS = 5 * 60_000L

        /** Whether [awayMs] away is long enough for the exit to be checked again; see [cameBack]. */
        internal fun exitStale(awayMs: Long): Boolean = awayMs >= RECHECK_AFTER_MS

        /**
         * Whether the exit check should run now: Tor on, its port answering, no verdict yet, no
         * check running, and the last one not too recent. Decided on the state as it stands, not
         * on what a probe found: Tor switched off while the port was probed gets no check.
         */
        internal fun checkDue(state: TorState, verifying: Boolean, now: Long, lastVerifyAt: Long): Boolean =
            state.enabled && state.status == TorStatus.ON && state.verifiedExit == null && !verifying && now - lastVerifyAt >= VERIFY_INTERVAL_MS

        /**
         * Whether an exit check begun on [port], when the listener had been found gone [lossesThen]
         * times, speaks for the listener in [state]: Tor still on, the same port, and the listener
         * not found gone since ([lossesNow]). Otherwise its answer came from what is no longer there.
         */
        internal fun speaksFor(state: TorState, port: Int, lossesThen: Long, lossesNow: Long): Boolean =
            state.enabled && state.socksPort == port && lossesNow == lossesThen

        /**
         * What a request held back by the exit check can expect: true when it may go out, false
         * when waiting would bring nothing (Orbot silent or missing, or a check that failed, which
         * is not run again by itself for a while), null while the check may still pass.
         */
        internal fun verdict(state: TorState): Boolean? = when {
            !state.blocking -> true
            state.verifyError != null -> false
            state.status == TorStatus.ON || state.status == TorStatus.CHECKING -> null
            else -> false
        }

        /** How long a held request waits for the check: a circuit to the Tor Project can take a minute to build. */
        private const val VERDICT_WAIT_MS = 90_000L
        private const val VERDICT_POLL_MS = 500L

        /** The exit check answers with a few bytes of JSON; anything bigger is not it. */
        private const val MAX_VERIFY_BODY = 64L * 1024
        private const val KEY_ENABLED = "tor"
        private const val KEY_PORT = "socks_port"
        private const val KEY_ROUTE_CHOSEN = "route_chosen"
    }
}

/**
 * Whether a SOCKS5 server answers on 127.0.0.1:[port]: the client greeting offering "no
 * authentication" must come back accepted. Any other listener, or none, counts as not running.
 */
fun socks5Listening(port: Int, timeoutMs: Int = 1500): Boolean = runCatching {
    java.net.Socket().use { socket ->
        socket.connect(InetSocketAddress("127.0.0.1", port), timeoutMs)
        socket.soTimeout = timeoutMs
        socket.getOutputStream().apply { write(byteArrayOf(5, 1, 0)); flush() }
        val reply = ByteArray(2)
        java.io.DataInputStream(socket.getInputStream()).readFully(reply)
        reply[0] == 5.toByte() && reply[1] == 0.toByte()
    }
}.getOrDefault(false)

/**
 * DNS for the app's HTTP client. Through Orbot's SOCKS proxy host names are never looked up
 * locally; this makes sure of it: while Tor is on, any local lookup is refused, so a request
 * that would resolve a name outside Tor fails instead of leaking it. Literal addresses (Orbot's
 * own 127.0.0.1) need no lookup and pass.
 */
class TorAwareDns(private val enabled: () -> Boolean) : okhttp3.Dns {
    override fun lookup(hostname: String): List<java.net.InetAddress> {
        if (enabled() && !isIpLiteral(hostname)) throw TorDnsBlockedException(hostname)
        // With Tor off an onion address leads nowhere, and asking the network's resolver for one
        // would tell it which hidden service the phone was after.
        if (isOnion(hostname)) throw OnionWithoutTorException(hostname)
        return okhttp3.Dns.SYSTEM.lookup(hostname)
    }

    companion object {
        fun isIpLiteral(host: String): Boolean =
            host.matches(Regex("\\d{1,3}(\\.\\d{1,3}){3}")) || (host.contains(':') && host.all { it.isLetterOrDigit() || it == ':' || it == '.' || it == '[' || it == ']' })
    }
}

/** Orbot's SOCKS port, the one way out while Tor is on: there is no direct route to fall back on. */
fun orbotProxy(port: Int): Proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))

/**
 * The app's HTTP client as its callers see it: each call is made by the client of the route in
 * force when it is made, with Tor on one whose only proxy is Orbot's SOCKS port, with Tor off one
 * that connects directly. Both are [base] otherwise, and share its dispatcher, pool, interceptors
 * and listener.
 *
 * The proxy is fixed in each client rather than chosen by a ProxySelector. OkHttp asks a selector
 * only about a URL in which java.net.URI finds a host, and sends any other out directly; for one
 * made of digits and dots, as "1.2.772" or "12.34", it then asks the platform's resolver itself,
 * past the client's own DNS. A site could put such a host in a file URL or a redirect and have
 * the phone ask its network about it, outside Tor. A fixed proxy is taken before the host is
 * looked at, and the name reaches Orbot unresolved.
 *
 * A call made while Tor was off and started once it is on is refused before any route is chosen.
 * Calls already under way are cancelled by the route change itself (see [TorManager.onRouteChanged]),
 * and until then [TorRouteGuard] and [TorAwareDns] refuse what would leave directly.
 */
class RoutedClient(
    private val base: OkHttpClient,
    private val enabled: () -> Boolean,
    private val port: () -> Int,
) : Call.Factory {
    private val direct = base.newBuilder()
        .proxy(Proxy.NO_PROXY)
        .addInterceptor { chain ->
            if (enabled()) throw TorUnreachableException(IOException("connection does not go through Orbot"))
            chain.proceed(chain.request())
        }
        .build()

    @Volatile
    private var orbotClient: OkHttpClient? = null

    /** The client a call made now is made by. */
    val current: OkHttpClient
        get() = if (enabled()) throughOrbot(port()) else direct

    override fun newCall(request: Request): Call = current.newCall(request)

    private fun throughOrbot(port: Int): OkHttpClient {
        val proxy = orbotProxy(port)
        orbotClient?.takeIf { it.proxy == proxy }?.let { return it }
        return base.newBuilder().proxy(proxy).build().also { orbotClient = it }
    }
}

/**
 * While Tor is on and its exit is not yet verified, lets out nothing but the exit check itself:
 * a request is refused before any connection is made, and [onBlocked] gives the manager a
 * chance to run the check. A SOCKS listener is not Orbot until the check says so.
 */
class TorExitGate(
    private val enabled: () -> Boolean,
    private val verified: () -> Boolean,
    private val onBlocked: () -> Unit = {},
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        if (enabled() && !verified() && chain.request().url.host != TorManager.VERIFY_HOST) {
            onBlocked()
            throw TorUnverifiedException()
        }
        return chain.proceed(chain.request())
    }
}

/**
 * Lets nothing out until the user has said how the app reaches sites: a request is refused before
 * any name is looked up or any connection made, whichever part of the app asked for it.
 */
class RouteChoiceGate(private val chosen: () -> Boolean) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        if (!chosen()) throw RouteNotChosenException()
        return chain.proceed(chain.request())
    }
}

/** A request held back because the user has not yet chosen between Tor and a direct connection. */
class RouteNotChosenException : IOException("No route chosen yet: nothing goes out before Tor or a direct connection is chosen")

/** A request held back because Tor is on and the exit check has not passed yet. */
class TorUnverifiedException : IOException("Tor exit not verified yet"), HasProblem {
    override val problem = Problem(Problem.Code.TOR_UNVERIFIED)
}

/**
 * Refuses an exchange on a connection that does not go through Orbot while Tor is on. The pool
 * is emptied when the route changes, but OkHttp offers a pooled connection to any request for
 * the same host whatever proxy the selector now names, so a direct connection opened just
 * before the switch could still carry a request made just after it. This closes that window:
 * the connection is refused and its socket closed, so it is never offered again.
 *
 * It also refuses an exchange with a listener that is not [verified] any more. [TorExitGate]
 * asks once per call, before anything connects; this runs once per attempt, so a redirect or a
 * retry of a call that was let through finds the verdict withdrawn meanwhile. By then the
 * listener has seen the host name in the CONNECT; it does not get the request, nor its cookies.
 *
 * And it lets the exit check out through the listener only, whether Tor is on or off: a check
 * on its way when Tor was switched off would otherwise go out directly.
 */
class TorRouteGuard(private val enabled: () -> Boolean, private val verified: () -> Boolean = { true }) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val connection = chain.connection()
        val throughListener = connection == null || connection.route().proxy.type() == Proxy.Type.SOCKS
        val exitCheck = chain.request().url.host == TorManager.VERIFY_HOST
        // The exit check goes nowhere but through the listener, Tor on or off: switched off while
        // the check was on its way, it would otherwise name the app to the Tor Project from the
        // home address.
        if (!throughListener && (enabled() || exitCheck)) {
            runCatching { connection.socket().close() }
            throw TorUnreachableException(IOException(if (exitCheck) "the exit check does not go through Orbot" else "connection does not go through Orbot"))
        }
        if (enabled() && !verified() && !exitCheck) throw TorUnverifiedException()
        return chain.proceed(chain.request())
    }
}

/**
 * Says what a request that failed with Tor on ran into. Only a port that no longer answers SOCKS5
 * makes it Tor unreachable, which tells the user to look at Orbot. A circuit too slow to build is
 * a timeout, and a site Tor cannot reach, a reset from the exit or a TLS failure stay the network
 * errors they are: Orbot is running, and checking it would be looking in the wrong place.
 *
 * Through Tor the connection also gets [connectTimeoutMs] instead of the client's own: it covers
 * the SOCKS exchange, whose answer waits for Tor to build a circuit to the site, and that takes
 * tens of seconds often enough, well past what a direct connection is given.
 */
class TorFailures(
    private val enabled: () -> Boolean,
    private val port: () -> Int,
    private val connectTimeoutMs: Int = TOR_CONNECT_TIMEOUT_MS,
    private val listening: (Int) -> Boolean = { socks5Listening(it) },
    /** Told when the port is found silent: see [TorManager.listenerLost]. */
    private val onListenerLost: () -> Unit = {},
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val through = if (enabled()) chain.withConnectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS) else chain
        try {
            return through.proceed(through.request())
        } catch (e: IOException) {
            // A failure that already says what it is, a call the user dropped, or Tor switched off
            // meanwhile: nothing to put down to the listener.
            if (e is HasProblem || !enabled() || chain.call().isCanceled()) throw e
            // A port found silent has lost what answered there: its verdict goes with it, or a
            // listener that takes the port next would be trusted without a check.
            if (!listening(port())) {
                onListenerLost()
                throw TorUnreachableException(e)
            }
            throw e
        }
    }

    companion object {
        /** Tor gives up building a circuit after about a minute; the connection waits as long. */
        const val TOR_CONNECT_TIMEOUT_MS = 60_000
    }
}

/**
 * Tells the manager when a connection through Orbot's port could not be made because of the
 * listener itself: see [TorManager.listenerLost]. Set on the app's client as its event listener.
 */
class TorListenerWatch(private val onListenerLost: () -> Unit) : EventListener() {
    override fun connectFailed(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?, ioe: IOException) {
        // A call cancelled while it connects fails on its own closed socket, which says nothing
        // of the listener: the grid scrolls on, a viewer closes, the vault locks.
        if (call.isCanceled()) return
        if (proxy.type() == Proxy.Type.SOCKS && socksListenerLost(ioe)) onListenerLost()
    }
}

/**
 * Whether a failure to connect through a SOCKS proxy blames the listener rather than the
 * network behind it. The Java SOCKS client reports a destination the proxy could not reach, or a
 * refusal of the proxy's own, as a [SocketException] whose message starts with "SOCKS"; a
 * connection nobody accepted, a reset, or a reply that is not SOCKS5 at all is anything else. A
 * timeout is neither: a slow circuit looks the same as a listener that never answers. Nor is a
 * TLS failure: the handshake runs through a tunnel the listener had already opened, and what
 * fails there is the site's certificate or an exit that cut the connection.
 */
internal fun socksListenerLost(e: IOException): Boolean = when {
    e is SocketTimeoutException -> false
    e is SSLException -> false
    e is SocketException && e.message.orEmpty().startsWith("SOCKS") -> false
    else -> true
}

/** Whether [host] is an onion address, which only Tor can reach. */
fun isOnion(host: String): Boolean = host.lowercase().trimEnd('.').endsWith(".onion")

/** An onion address asked for with Tor off: only Tor knows the way, and no resolver is asked. */
class OnionWithoutTorException(host: String) :
    java.net.UnknownHostException("An onion address is reached only through Tor ($host)"), HasProblem {
    override val problem = Problem(Problem.Code.ONION_NEEDS_TOR, host)
}

/** A local name lookup refused because Tor is on. */
class TorDnsBlockedException(host: String) :
    java.net.UnknownHostException("Local DNS blocked: with Tor on, names resolve only through Tor ($host)"), HasProblem {
    override val problem = Problem(Problem.Code.TOR_DNS_BLOCKED, host)
}

/** A connection that could not be made while every connection must go through Orbot. */
class TorUnreachableException(cause: IOException) : IOException("Tor unreachable: ${cause.message}", cause), HasProblem {
    override val problem = Problem(Problem.Code.TOR_UNREACHABLE, cause.message)
}
