package app.booruvault.net

import app.booruvault.vault.SealedJson
import app.booruvault.vault.VaultManager
import app.booruvault.vault.VaultState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
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
private data class CookieState(
    val cookies: List<StoredCookie> = emptyList(),
    /** Site → User-Agent that passed its anti-bot check; Cloudflare binds clearance to it. */
    val userAgents: Map<String, String> = emptyMap(),
)

/**
 * Site cookies and anti-bot clearances.
 *
 * Only the cookies the user brought from the built-in browser (logins, site preferences,
 * clearances) are kept, sealed in the vault (`cookies.bin`). Cookies sites set on their own while
 * the app browses last for the session in memory: kept, they would be an identifier that follows
 * the user from one session to the next. A site renewing a kept cookie renews the kept copy.
 * Nothing is in memory while locked, unless downloads allowed to run behind the lock need it.
 */
class SecureCookieJar(root: File, private val vault: VaultManager, json: Json, scope: CoroutineScope) : CookieJar {
    private val store = SealedJson(File(root, "cookies.bin"), CookieState.serializer(), json)
    private val lock = Any()
    private val cookies = ArrayList<Cookie>()

    /** name|domain|path of the cookies brought from the built-in browser. */
    private val kept = HashSet<String>()
    private val userAgents = HashMap<String, String>()
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
                        synchronized(lock) {
                            cookies.clear()
                            kept.clear()
                            userAgents.clear()
                            val now = System.currentTimeMillis()
                            stored?.cookies?.mapNotNull { it.toCookie() }?.filter { it.expiresAt > now }?.forEach {
                                cookies += it
                                kept += it.key()
                            }
                            stored?.userAgents?.let(userAgents::putAll)
                            loaded = true
                        }
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

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val renewsKept = synchronized(lock) {
            if (!loaded) return
            cookies.forEach(::put)
            cookies.any { it.key() in kept }
        }
        if (renewsKept) saves.trySend(Unit)
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> = synchronized(lock) {
        if (!loaded) return emptyList()
        val now = System.currentTimeMillis()
        cookies.removeAll { it.expiresAt <= now }
        cookies.filter { it.matches(url) }
    }

    fun userAgentFor(host: String): String? = synchronized(lock) { userAgents[siteOf(host)] }

    /** Keeps what the user earned in the challenge WebView, then the WebView's own copy is wiped. */
    fun recordClearance(host: String, userAgent: String, webCookies: List<Cookie>) {
        synchronized(lock) {
            if (!loaded) return
            userAgents[siteOf(host)] = userAgent
            webCookies.forEach {
                put(it)
                kept += it.key()
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
            cookies.clear()
            kept.clear()
            userAgents.clear()
        }
    }

    fun clear() {
        synchronized(lock) {
            cookies.clear()
            kept.clear()
            userAgents.clear()
        }
        saves.trySend(Unit)
    }

    private fun put(cookie: Cookie) {
        cookies.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
        if (cookie.expiresAt > System.currentTimeMillis()) cookies += cookie
    }

    private fun save() {
        val recipient = vault.recipient ?: return
        val snapshot = synchronized(lock) {
            if (!loaded) return
            CookieState(cookies.filter { it.key() in kept }.map { it.toStored() }, userAgents.toMap())
        }
        runCatching { store.write(recipient, snapshot) }
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
        /** "static1.e621.net" and "e621.net" share their clearance. */
        fun siteOf(host: String): String = host.lowercase().split('.').takeLast(2).joinToString(".")
    }
}
