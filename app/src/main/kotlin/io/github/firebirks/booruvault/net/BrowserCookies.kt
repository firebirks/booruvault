package io.github.firebirks.booruvault.net

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** What the engine's extension answers when asked for cookies: for each address, names, values and whether a page's script can reach them. */
@Serializable
internal data class BrowserCookies(val id: Int = 0, val error: String? = null, val cookies: Map<String, List<Entry>> = emptyMap()) {
    @Serializable
    data class Entry(val name: String, val value: String, val httpOnly: Boolean = false)

    /**
     * The cookies of [site] among them, as the app keeps them: see [Challenge.browserCookie]. An
     * address outside [site] contributes nothing.
     */
    fun of(site: String, expires: Long): List<Cookie> {
        val found = LinkedHashMap<String, Cookie>()
        for ((address, entries) in cookies) {
            val url = address.toHttpUrlOrNull() ?: continue
            if (SecureCookieJar.siteOf(url.host.lowercase()) != site) continue
            for (entry in entries) {
                if (entry.name.isEmpty()) continue
                Challenge.browserCookie(entry.name, entry.value, url, site, expires, entry.httpOnly)?.let { found["${entry.name}|${it.domain}"] = it }
            }
        }
        return found.values.toList()
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): BrowserCookies = json.decodeFromString(serializer(), text)
    }
}
