package io.github.firebirks.booruvault.net

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import io.github.firebirks.booruvault.booru.USER_AGENT
import io.github.firebirks.booruvault.booru.await
import io.github.firebirks.booruvault.util.HasProblem
import io.github.firebirks.booruvault.util.Problem
import io.github.firebirks.booruvault.util.toProblem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.boolean
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

enum class TorStatus { DISABLED, NOT_INSTALLED, STARTING, ON, OFF, STARTS_DISABLED }

data class TorState(
    val enabled: Boolean,
    val status: TorStatus,
    /** The port the app connects to: a setting, never taken from a broadcast. */
    val socksPort: Int = DEFAULT_SOCKS_PORT,
    /** The port Orbot's last status message named. Shown to the user only; it is not trusted. */
    val reportedPort: Int? = null,
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
 * Orbot's status broadcasts are not protected by a permission, so any app on the phone can send
 * one. They are therefore taken as information for the user only: the port the app connects
 * to is the configured one, and the status shown is confirmed only by the exit check.
 */
class TorManager(private val context: Context) {
    private val prefs = context.getSharedPreferences("network", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(
        prefs.getBoolean(KEY_ENABLED, false).let { enabled ->
            TorState(
                enabled,
                if (enabled) TorStatus.STARTING else TorStatus.DISABLED,
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

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_STATUS) return
            val status = when (intent.getStringExtra(EXTRA_STATUS)) {
                "ON" -> TorStatus.ON
                "STARTING" -> TorStatus.STARTING
                "STARTS_DISABLED" -> TorStatus.STARTS_DISABLED
                else -> TorStatus.OFF
            }
            // The sender cannot be verified, so the port named here is only shown, never used.
            val port = when (val value = intent.extras?.get(EXTRA_SOCKS_PORT)) {
                is Int -> value
                is String -> value.toIntOrNull()
                else -> null
            }?.takeIf { it in 1..65535 }
            _state.update {
                if (!it.enabled) it else it.copy(status = status, reportedPort = port ?: it.reportedPort)
            }
        }
    }

    init {
        ContextCompat.registerReceiver(context, receiver, IntentFilter(ACTION_STATUS), ContextCompat.RECEIVER_EXPORTED)
        if (enabled) requestStart()
    }

    fun isOrbotInstalled(): Boolean = try {
        context.packageManager.getPackageInfo(ORBOT_PACKAGE, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    fun setEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, value).commit()
        _state.value = TorState(value, if (value) TorStatus.STARTING else TorStatus.DISABLED, socksPort = _state.value.socksPort)
        onRouteChanged()
        if (value) requestStart()
    }

    /** Sets the local port the app connects to Orbot on. Returns false for an invalid port. */
    fun setSocksPort(port: Int): Boolean {
        if (port !in 1..65535) return false
        if (port == _state.value.socksPort) return true
        prefs.edit().putInt(KEY_PORT, port).commit()
        _state.update { it.copy(socksPort = port, verifiedExit = null, verifyError = null) }
        onRouteChanged()
        return true
    }

    /** Asks Orbot to start and to report its status and ports. */
    fun requestStart() {
        if (!enabled) return
        if (!isOrbotInstalled()) {
            _state.update { it.copy(status = TorStatus.NOT_INSTALLED) }
            return
        }
        _state.update { if (it.status == TorStatus.ON) it else it.copy(status = TorStatus.STARTING) }
        context.sendBroadcast(
            Intent(ACTION_START).setPackage(ORBOT_PACKAGE).putExtra(EXTRA_PACKAGE_NAME, context.packageName),
        )
    }

    fun orbotLaunchIntent(): Intent? = context.packageManager.getLaunchIntentForPackage(ORBOT_PACKAGE)

    /** Asks the Tor Project whether requests reach it through Tor. */
    suspend fun verify(client: OkHttpClient, json: Json) = withContext(Dispatchers.IO) {
        _state.update { it.copy(verifiedExit = null, verifyError = null) }
        try {
            val request = Request.Builder().url("https://check.torproject.org/api/ip").header("User-Agent", USER_AGENT).build()
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

        // Orbot's integration protocol (as used by Guardian Project's NetCipher).
        private const val ACTION_START = "org.torproject.android.intent.action.START"
        private const val ACTION_STATUS = "org.torproject.android.intent.action.STATUS"
        private const val EXTRA_PACKAGE_NAME = "org.torproject.android.intent.extra.PACKAGE_NAME"
        private const val EXTRA_STATUS = "org.torproject.android.intent.extra.STATUS"
        private const val EXTRA_SOCKS_PORT = "org.torproject.android.intent.extra.SOCKS_PROXY_PORT"
    }
}

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

/** A local name lookup refused because Tor is on. */
class TorDnsBlockedException(host: String) :
    java.net.UnknownHostException("Local DNS blocked: with Tor on, names resolve only through Tor ($host)"), HasProblem {
    override val problem = Problem(Problem.Code.TOR_DNS_BLOCKED, host)
}

/** A connection that could not be made while every connection must go through Orbot. */
class TorUnreachableException(cause: IOException) : IOException("Tor unreachable: ${cause.message}", cause), HasProblem {
    override val problem = Problem(Problem.Code.TOR_UNREACHABLE, cause.message)
}
