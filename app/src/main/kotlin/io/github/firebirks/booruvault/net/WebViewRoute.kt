package io.github.firebirks.booruvault.net

import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Points the app's WebViews at Orbot when Tor is on, with no direct fallback. */
object WebViewRoute {
    /**
     * Applies the route before any page loads. With Tor on, a WebView that cannot be proxied, or
     * cannot have WebRTC removed before page scripts run, is not opened at all: either would let
     * traffic out around Tor.
     */
    suspend fun apply(tor: TorManager): Result<Unit> {
        if (!tor.enabled) {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
                suspendCancellableCoroutine { cont ->
                    ProxyController.getInstance().clearProxyOverride({ it.run() }) { cont.resume(Unit) }
                }
            }
            return Result.success(Unit)
        }
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        ) {
            return Result.failure(IllegalStateException("WebView cannot be routed entirely through Tor"))
        }
        // The same rule as the HTTP client: a SOCKS listener is not Tor until the exit check says so.
        if (!tor.verified) {
            tor.refreshStatus()
            return Result.failure(TorUnverifiedException())
        }
        // socks5:// lets Tor resolve host names; no direct rule, so nothing goes around it.
        val config = ProxyConfig.Builder().addProxyRule("socks5://127.0.0.1:${tor.state.value.socksPort}").build()
        suspendCancellableCoroutine { cont ->
            ProxyController.getInstance().setProxyOverride(config, { it.run() }) { cont.resume(Unit) }
        }
        return Result.success(Unit)
    }
}
