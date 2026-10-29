package io.github.firebirks.booruvault.net

import okhttp3.Connection
import java.net.Proxy

/**
 * The two ways out: straight to the site, or through Tor. What a site hands the app along one
 * route (cookies, an anti-bot pass, the agent that earned it, a TLS session) is never offered
 * along the other: it would tell the site that the Tor user and the one at the home address are
 * the same person.
 */
enum class Route {
    DIRECT,
    TOR,
    ;

    companion object {
        fun of(torEnabled: Boolean): Route = if (torEnabled) TOR else DIRECT

        /** The route [connection] really takes: through Orbot's SOCKS proxy, or not. */
        fun of(connection: Connection): Route = if (connection.route().proxy.type() == Proxy.Type.SOCKS) TOR else DIRECT
    }
}
