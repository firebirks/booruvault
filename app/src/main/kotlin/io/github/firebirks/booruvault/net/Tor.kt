package io.github.firebirks.booruvault.net

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import io.github.firebirks.booruvault.booru.UserAgents
import io.github.firebirks.booruvault.booru.await
import io.github.firebirks.booruvault.util.HasProblem
import io.github.firebirks.booruvault.util.Problem
import io.github.firebirks.booruvault.util.toProblem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

enum class TorStatus { DISABLED, NOT_INSTALLED, CHECKING, ON, OFF }

data class TorState(
    val enabled: Boolean,
    val status: TorStatus,
    /** The port the app connects to: a setting. */
    val socksPort: Int = DEFAULT_SOCKS_PORT,
    /** Result of the last check against check.torproject.org: exit address, or null if unchecked. */
    val verifiedExit: String? = null,
    val verifyError: Problem? = null,
)

const val DEFAULT_SOCKS_PORT = 9050

/**
 * Routes the app through Tor via Orbot's SOCKS proxy.
 *
 * Fail-closed: while enabled, the proxy selector offers only Orbot's port, with no direct
 * fallback, so if Orbot is not running every connection fails instead of going out in clear.
 * Host names reach the proxy unresolved, so DNS goes through Tor as well.
 *
 * The on/off flag and the port are the settings kept outside the vault (`network.xml`): they
 * must hold from process start, before any unlock, so that no connection can ever slip out
 * directly. They reveal only that the app is set to use Tor, and where.
 *
 * Orbot is neither started nor asked anything: since Orbot 17.9.6 other apps cannot start it,
 * and its status broadcasts, which any app could forge, are gone with that. Whether it listens
 * is found out by greeting the port as a SOCKS5 client; whether traffic really leaves through
 * Tor, by the exit check.
 */
class TorManager(private val context: Context, private val scope: CoroutineScope) {
    private val prefs = context.getSharedPreferences("network", Context.MODE_PRIVATE)

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

    val proxySelector: ProxySelector = TorProxySelector({ enabled }, { _state.value.socksPort })

    val dns: okhttp3.Dns = TorAwareDns { enabled }

    /** Called when the route changes, so pooled connections opened the other way are dropped. */
    var onRouteChanged: () -> Unit = {}

    init {
        if (enabled) refreshStatus()
    }

    fun isOrbotInstalled(): Boolean = try {
        context.packageManager.getPackageInfo(ORBOT_PACKAGE, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    fun setEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, value).commit()
        _state.value = TorState(value, if (value) TorStatus.CHECKING else TorStatus.DISABLED, socksPort = _state.value.socksPort)
        onRouteChanged()
        if (value) refreshStatus()
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

    /** Finds out whether Orbot is installed and a SOCKS5 server answers on the port. */
    fun refreshStatus() {
        if (!enabled) return
        scope.launch(Dispatchers.IO) {
            val status = when {
                !isOrbotInstalled() -> TorStatus.NOT_INSTALLED
                socks5Listening(_state.value.socksPort) -> TorStatus.ON
                else -> TorStatus.OFF
            }
            _state.update { if (it.enabled) it.copy(status = status) else it }
        }
    }

    fun orbotLaunchIntent(): Intent? = context.packageManager.getLaunchIntentForPackage(ORBOT_PACKAGE)

    /** Asks the Tor Project whether requests reach it through Tor. */
    suspend fun verify(client: OkHttpClient, json: Json) = withContext(Dispatchers.IO) {
        _state.update { it.copy(verifiedExit = null, verifyError = null) }
        try {
            val request = Request.Builder().url("https://check.torproject.org/api/ip").header("User-Agent", UserAgents.browser).build()
            val body = client.newCall(request).await().use { it.body.string() }
            val result = json.parseToJsonElement(body).jsonObject
            val isTor = result["IsTor"]?.jsonPrimitive?.boolean == true
            val ip = result["IP"]?.jsonPrimitive?.content
            _state.update {
                if (isTor) it.copy(verifiedExit = ip ?: "?") else it.copy(verifyError = Problem(Problem.Code.NOT_THROUGH_TOR))
            }
        } catch (e: Exception) {
            _state.update { it.copy(verifyError = e.toProblem()) }
        }
    }

    companion object {
        const val ORBOT_PACKAGE = "org.torproject.android"
        private const val KEY_ENABLED = "tor"
        private const val KEY_PORT = "socks_port"
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
        return okhttp3.Dns.SYSTEM.lookup(hostname)
    }

    companion object {
        fun isIpLiteral(host: String): Boolean =
            host.matches(Regex("\\d{1,3}(\\.\\d{1,3}){3}")) || (host.contains(':') && host.all { it.isLetterOrDigit() || it == ':' || it == '.' || it == '[' || it == ']' })
    }
}

/** Orbot's SOCKS port while enabled, and nothing else: no direct route to fall back on. */
class TorProxySelector(private val enabled: () -> Boolean, private val port: () -> Int) : ProxySelector() {
    override fun select(uri: URI?): List<Proxy> =
        if (enabled()) listOf(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port()))) else listOf(Proxy.NO_PROXY)

    override fun connectFailed(uri: URI?, address: SocketAddress?, e: IOException?) = Unit
}

/**
 * Refuses an exchange on a connection that does not go through Orbot while Tor is on. The pool
 * is emptied when the route changes, but OkHttp offers a pooled connection to any request for
 * the same host whatever proxy the selector now names, so a direct connection opened just
 * before the switch could still carry a request made just after it. This closes that window:
 * the connection is refused and its socket closed, so it is never offered again.
 */
class TorRouteGuard(private val enabled: () -> Boolean) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val connection = chain.connection()
        if (enabled() && connection != null && connection.route().proxy.type() != Proxy.Type.SOCKS) {
            runCatching { connection.socket().close() }
            throw TorUnreachableException(IOException("connection does not go through Orbot"))
        }
        return chain.proceed(chain.request())
    }
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
