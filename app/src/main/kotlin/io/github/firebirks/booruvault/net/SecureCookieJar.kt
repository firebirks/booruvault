package io.github.firebirks.booruvault.net

import io.github.firebirks.booruvault.booru.SitePasses
import io.github.firebirks.booruvault.util.DebugLog
import io.github.firebirks.booruvault.vault.SealedJson
import io.github.firebirks.booruvault.vault.VaultManager
import io.github.firebirks.booruvault.vault.VaultState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.internal.publicsuffix.PublicSuffixDatabase
import java.io.File

@Serializable
private data class StoredCookie(
    val name: String,
    val value: String,
    val expiresAt: Long,
    val domain: String,
    val path: String,
    val secure: Boolean,
    val httpOnly: Boolean,
    val hostOnly: Boolean,
)

@Serializable
private data class RouteCookies(
    val cookies: List<StoredCookie> = emptyList(),
    /** Site → User-Agent that passed its anti-bot check; Cloudflare binds clearance to it. */
    val userAgents: Map<String, String> = emptyMap(),
)

@Serializable
private data class CookieState(
    /** What builds that kept one jar for both routes stored. */
    val cookies: List<StoredCookie> = emptyList(),
    val userAgents: Map<String, String> = emptyMap(),
    val direct: RouteCookies? = null,
    val tor: RouteCookies? = null,
)

/**
 * Site cookies and anti-bot clearances.
 *
 * Only the cookies the user brought from the built-in browser (logins, site preferences,
 * clearances) are kept, sealed in the vault (`cookies.bin`). Cookies sites set on their own while
 * the app browses last for the session in memory: kept, they would be an identifier that follows
 * the user from one session to the next. A site renewing a kept cookie renews the kept copy.
 * Nothing is in memory while locked, unless downloads allowed to run behind the lock need it.
 *
 * Each [Route] has a jar of its own, and nothing in one is ever offered along the other: a
 * session, a clearance or the agent that earned it, shown from the home address after a Tor exit,
 * would tell the site both are the same person. Switching back finds the route's jar as it was.
 * What builds before the split stored is given to the route in use when it is first read.
 */
class SecureCookieJar(
    root: File,
    private val vault: VaultManager,
    json: Json,
    scope: CoroutineScope,
    private val currentRoute: () -> Route,
) : SitePasses {
    private val store = SealedJson(File(root, "cookies.bin"), CookieState.serializer(), json)
    private val lock = Any()
    private val jars = Route.entries.associateWith { RouteJar() }

    /** One route's share. */
    private class RouteJar {
        val cookies = ArrayList<Cookie>()

        /** name|domain|path of the cookies brought from the built-in browser. */
        val kept = HashSet<String>()
        val userAgents = HashMap<String, String>()

        /** Clearances that have brought back an answer: the site took them, for a while. Memory only. */
        val worked = HashSet<String>()

        /** Site → the last clearance it refused: whether it had worked before, and when. Memory only. */
        val refusals = HashMap<String, Refusal>()

        fun clear() {
            cookies.clear()
            kept.clear()
            userAgents.clear()
            worked.clear()
            refusals.clear()
        }
    }

    private class Refusal(val hadWorked: Boolean, val at: Long)
    private var loaded = false
    private val saves = Channel<Unit>(Channel.CONFLATED)

    /** Consulted when the vault locks: true keeps cookies in memory for downloads that go on. */
    @Volatile
    var retainWhileLocked: () -> Boolean = { false }

    init {
        scope.launch(Dispatchers.IO) {
            vault.state.collect { state ->
                when (state) {
                    is VaultState.Unlocked -> {
                        val stored = store.read(state.keys)
                        val legacy = stored?.takeIf { it.direct == null && it.tor == null && (it.cookies.isNotEmpty() || it.userAgents.isNotEmpty()) }
                            ?.let { RouteCookies(it.cookies, it.userAgents) }
                        synchronized(lock) {
                            jars.values.forEach { it.clear() }
                            val now = System.currentTimeMillis()
                            for ((route, jar) in jars) {
                                val share = when {
                                    legacy != null -> legacy.takeIf { route == currentRoute() }
                                    route == Route.TOR -> stored?.tor
                                    else -> stored?.direct
                                } ?: continue
                                share.cookies.mapNotNull { it.toCookie() }.filter { it.expiresAt > now }.forEach {
                                    jar.cookies += it
                                    jar.kept += it.key()
                                }
                                jar.userAgents.putAll(share.userAgents)
                            }
                            loaded = true
                        }
                        // Written at once in the split form, so the old one is never read again.
                        if (legacy != null) saves.trySend(Unit)
                    }
                    VaultState.Locked -> if (!retainWhileLocked()) forget()
                    VaultState.Missing -> forget()
                }
            }
        }
        scope.launch(Dispatchers.IO) {
            for (request in saves) {
                save()
                delay(1000)
            }
        }
    }

    override fun saveFromResponse(route: Route, url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        val renewsKept = synchronized(lock) {
            if (!loaded) return
            val jar = jars.getValue(route)
            cookies.forEach { jar.put(it) }
            cookies.any { it.key() in jar.kept }
        }
        if (renewsKept) saves.trySend(Unit)
    }

    override fun cookiesFor(route: Route, url: HttpUrl): List<Cookie> = synchronized(lock) {
        if (!loaded) return emptyList()
        val jar = jars.getValue(route)
        val now = System.currentTimeMillis()
        jar.cookies.removeAll { it.expiresAt <= now }
        jar.cookies.filter { it.matches(url) }
    }

    override fun userAgentFor(route: Route, host: String): String? = synchronized(lock) { jars.getValue(route).userAgents[siteOf(host)] }

    override fun clearanceWorked(route: Route, host: String, value: String) {
        synchronized(lock) {
            if (loaded && jars.getValue(route).worked.add(value)) DebugLog.d { "clearance of ${siteOf(host)} worked ($route)" }
        }
    }

    /**
     * Whether [host]'s site refused along [route], at or after [since], a clearance that had worked
     * before: it ran out, or the address it was bound to changed, and passing the check again brings
     * one that works. A clearance refused before it ever worked says the opposite.
     */
    fun passExpiredSince(route: Route, host: String, since: Long): Boolean = synchronized(lock) {
        jars.getValue(route).refusals[siteOf(host)]?.let { it.hadWorked && it.at >= since } == true
    }

    /**
     * Forgets the clearance [value] that [host]'s site refused along [route], and, once the site
     * holds no other there, the agent that earned it: its requests then go out under their own
     * name again. A clearance brought back since, with another value, stays.
     */
    override fun dropClearance(route: Route, host: String, value: String) {
        val site = siteOf(host)
        synchronized(lock) {
            if (!loaded) return
            val jar = jars.getValue(route)
            val refused = jar.cookies.filter { it.name == Challenge.CLEARANCE_COOKIE && it.value == value && siteOf(it.domain) == site }
            if (refused.isEmpty()) return
            jar.cookies.removeAll(refused.toSet())
            refused.forEach { jar.kept -= it.key() }
            val agentGone = jar.cookies.none { it.name == Challenge.CLEARANCE_COOKIE && siteOf(it.domain) == site }
            if (agentGone) jar.userAgents.remove(site)
            val hadWorked = jar.worked.remove(value)
            jar.refusals[site] = Refusal(hadWorked, System.currentTimeMillis())
            DebugLog.d { "clearance of $site dropped ($route), agent dropped too: $agentGone, had worked: $hadWorked" }
        }
        saves.trySend(Unit)
    }

    /**
     * Keeps what the user earned in the challenge WebView, which loaded along [route], for that
     * route; then the WebView's own copy is wiped.
     */
    fun recordClearance(route: Route, host: String, userAgent: String, webCookies: List<Cookie>) {
        synchronized(lock) {
            if (!loaded) return
            val jar = jars.getValue(route)
            jar.userAgents[siteOf(host)] = userAgent
            webCookies.forEach {
                jar.put(it)
                jar.kept += it.key()
            }
        }
        saves.trySend(Unit)
    }

    /** Drops the in-memory copy once nothing needs it while the vault is locked. */
    fun releaseIfLocked() {
        if (vault.state.value !is VaultState.Unlocked) forget()
    }

    private fun forget() {
        save()
        synchronized(lock) {
            loaded = false
            jars.values.forEach { it.clear() }
        }
    }

    /** The kept cookies of [route] for [url], as Set-Cookie strings for the hidden browser. */
    fun browserCookies(route: Route, url: HttpUrl): List<String> = synchronized(lock) {
        if (!loaded) return emptyList()
        val jar = jars.getValue(route)
        jar.cookies.filter { it.key() in jar.kept && it.matches(url) }.map { it.toString() }
    }

    /**
     * Keeps what a hidden visit along [route] renewed: cookies already kept and a fresh clearance.
     * Whatever else the site set during the visit is not kept.
     */
    fun renewFromBrowser(route: Route, host: String, webCookies: List<Cookie>) {
        val renewed = synchronized(lock) {
            if (!loaded) return
            val jar = jars.getValue(route)
            val keptNames = jar.cookies.filter { it.key() in jar.kept }.mapTo(HashSet()) { it.name }
            webCookies.filter { it.name in keptNames || it.name == Challenge.CLEARANCE_COOKIE }.onEach {
                jar.put(it)
                jar.kept += it.key()
            }
        }
        if (renewed.isNotEmpty()) saves.trySend(Unit)
    }

    fun clear() {
        synchronized(lock) {
            jars.values.forEach { it.clear() }
        }
        saves.trySend(Unit)
    }

    private fun RouteJar.put(cookie: Cookie) {
        cookies.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
        if (cookie.expiresAt > System.currentTimeMillis()) cookies += cookie
    }

    private fun save() {
        val recipient = vault.recipient ?: return
        fun RouteJar.stored() = RouteCookies(cookies.filter { it.key() in kept }.map { it.toStored() }, userAgents.toMap())
        runCatching {
            store.write(recipient) {
                synchronized(lock) {
                    if (loaded) CookieState(direct = jars.getValue(Route.DIRECT).stored(), tor = jars.getValue(Route.TOR).stored()) else null
                }
            }
        }
    }

    private fun Cookie.key() = "$name|$domain|$path"

    private fun Cookie.toStored() = StoredCookie(name, value, expiresAt, domain, path, secure, httpOnly, hostOnly)

    private fun StoredCookie.toCookie(): Cookie? = runCatching {
        Cookie.Builder().name(name).value(value).expiresAt(expiresAt).path(path)
            .apply {
                if (hostOnly) hostOnlyDomain(domain) else domain(domain)
                if (secure) secure()
                if (httpOnly) httpOnly()
            }.build()
    }.getOrNull()

    companion object {
        /**
         * The registrable domain of [host]: "static1.e621.net" and "e621.net" share their
         * clearance, while "a.github.io" and "b.github.io" share nothing. A host that is itself a
         * public suffix, or an address, stands alone.
         */
        fun siteOf(host: String): String {
            val lower = host.lowercase()
            // The list knows names only: an address would come back as its last two numbers.
            if (TorAwareDns.isIpLiteral(lower)) return lower
            return runCatching { suffixes?.getEffectiveTldPlusOne(lower) }.getOrNull() ?: lower
        }

        /** OkHttp's public suffix list, from its Android assets. Null if it cannot load: every host then stands alone. */
        private val suffixes: PublicSuffixDatabase? by lazy {
            runCatching { PublicSuffixDatabase.get().also { it.getEffectiveTldPlusOne("example.com") } }.getOrNull()
        }
    }
}
