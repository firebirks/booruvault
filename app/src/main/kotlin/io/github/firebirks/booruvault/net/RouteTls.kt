package io.github.firebirks.booruvault.net

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.security.KeyStore
import javax.net.SocketFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * TLS for the app's client with one session cache per route. A session opened along one route
 * and resumed along the other would show the site that a connection from the home address and
 * one from a Tor exit belong together. OkHttp makes a direct connection's socket with the
 * client's [socketFactory] and a SOCKS connection's socket itself, so the class of the socket
 * being wrapped says which route it takes.
 */
class RouteTls {
    val trustManager: X509TrustManager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        .apply { init(null as KeyStore?) }
        .trustManagers.filterIsInstance<X509TrustManager>().first()

    private val direct = newFactory()
    private val tor = newFactory()

    /** Makes the sockets of direct connections, so they can be told apart. */
    val socketFactory: SocketFactory = object : SocketFactory() {
        override fun createSocket() = DirectSocket()
        override fun createSocket(host: String, port: Int) = DirectSocket().apply { connect(InetSocketAddress(host, port)) }
        override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int) =
            DirectSocket().apply {
                bind(InetSocketAddress(localHost, localPort))
                connect(InetSocketAddress(host, port))
            }
        override fun createSocket(host: InetAddress, port: Int) = DirectSocket().apply { connect(InetSocketAddress(host, port)) }
        override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int) =
            DirectSocket().apply {
                bind(InetSocketAddress(localAddress, localPort))
                connect(InetSocketAddress(address, port))
            }
    }

    /** Wraps a connected socket with the TLS of the route it takes. */
    val sslSocketFactory: SSLSocketFactory = object : SSLSocketFactory() {
        override fun createSocket(socket: Socket, host: String, port: Int, autoClose: Boolean): Socket =
            factoryFor(socket).createSocket(socket, host, port, autoClose)

        override fun getDefaultCipherSuites(): Array<String> = direct.defaultCipherSuites
        override fun getSupportedCipherSuites(): Array<String> = direct.supportedCipherSuites

        // OkHttp only ever wraps a socket it connected; one made here would have no route.
        override fun createSocket(host: String, port: Int): Socket = throw SocketException("no route")
        override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket = throw SocketException("no route")
        override fun createSocket(host: InetAddress, port: Int): Socket = throw SocketException("no route")
        override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket = throw SocketException("no route")
    }

    /** The factory, and with it the session cache, for the route [socket] takes. */
    internal fun factoryFor(socket: Socket): SSLSocketFactory = if (socket is DirectSocket) direct else tor

    private fun newFactory(): SSLSocketFactory = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }.socketFactory

    /** A socket of a direct connection. */
    internal class DirectSocket : Socket()
}
