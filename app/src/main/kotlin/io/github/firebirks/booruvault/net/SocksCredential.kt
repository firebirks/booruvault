package io.github.firebirks.booruvault.net

import java.net.Authenticator
import java.net.PasswordAuthentication

/**
 * What the app tells Orbot's SOCKS port about itself. Tor keeps streams that come with different
 * credentials on different circuits (IsolateSOCKSAuth, on by default), and a circuit has one
 * exit. Left to themselves the app's client and the built-in browsers' engine say different
 * things: Java sends the name of the system user, the engine sends nothing. A check passed in
 * the browser then comes from one exit and the app's next request from another, and a site may
 * refuse from the second what it granted to the first. Both are told to say the same, so both
 * travel together.
 *
 * It is one credential for everything the app sends, as it was before: sites are not kept apart
 * from each other by it.
 */
object SocksCredential {
    const val USER = "booruvault"
    const val PASSWORD = "booruvault"

    /**
     * Makes Java's SOCKS client, which OkHttp's connections through Orbot use, send the
     * credential. The authenticator is the process's one, and is asked for nothing else here:
     * OkHttp answers HTTP's own authentication itself.
     */
    fun install() {
        Authenticator.setDefault(object : Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication? =
                if (requestingProtocol.equals("SOCKS5", ignoreCase = true)) PasswordAuthentication(USER, PASSWORD.toCharArray()) else null
        })
    }
}
