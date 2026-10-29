package io.github.firebirks.booruvault.booru

import io.github.firebirks.booruvault.BuildConfig
import io.github.firebirks.booruvault.net.Challenge
import io.github.firebirks.booruvault.net.Route
import io.github.firebirks.booruvault.util.DebugLog
import io.github.firebirks.booruvault.util.Problem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * What the app calls itself to sites: Chrome on Android, in the reduced form Chrome itself sends,
 * which names no phone model or Android version. The app's traffic then reads like a phone
 * browsing, and nothing about the device travels with it. The Chrome version is read from the
 * phone's WebView at start-up, so it keeps up without a release.
 */
object UserAgents {
    /** Chrome's major version; [DEFAULT_CHROME] until the WebView has been asked. */
    @Volatile
    var chrome: String = DEFAULT_CHROME

    /** Chrome's own name, for the HTTP client. */
    val browser: String
        get() = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chrome.0.0.0 Mobile Safari/537.36"

    /** The same name as Chrome's WebView sends it, for the built-in browser. */
    val webView: String
        get() = "Mozilla/5.0 (Linux; Android 10; K; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/$chrome.0.0.0 Mobile Safari/537.36"

    /**
     * The name for [source]'s site. e621 asks API clients for a name of their own, so for it the
     * app's name and version ride along at the end. Not the account's, which e621 only suggests:
     * the account already signs the API calls, and the name also goes with every image fetched
     * without it. Danbooru boards get the app's name alone, without a version: Cloudflare on
     * cdn.donmai.us puts a check in front of original files for a client that calls itself Chrome
     * and is not, and lets one that names itself through. The built-in browser is a browser and
     * says so on every site, as its name reaches every page and script it loads.
     */
    fun forSource(source: BooruSource?, webView: Boolean = false): String {
        if (webView) return this.webView
        return when (source?.engine) {
            BooruEngine.DANBOORU -> APP
            BooruEngine.E621 -> "$browser $APP/${BuildConfig.VERSION_NAME}"
            else -> browser
        }
    }

    /** The app's own name, for sites it presents itself to as an app rather than a browser. */
    const val APP = "BooruVault"

    const val DEFAULT_CHROME = "130"
}

/**
 * [request] as the app's client sends it. A site whose anti-bot check the user passed only honours
 * the browser that passed it, so the [passedAgent] recorded for the site wins. A request that names
 * the app keeps its name only while the site holds no clearance ([cleared]): a clearance is bound to
 * the browser that earned it, and sent under another name it is refused. Some image hosts
 * (img*.gelbooru.com) redirect a request without a Referer to an HTML hotlink page; their own origin
 * satisfies them and says nothing about the user.
 */
fun withSiteHeaders(request: Request, passedAgent: String?, cleared: Boolean, cookies: List<Cookie> = emptyList()): Request {
    val own = request.header("User-Agent")
    val agent = own?.takeIf { it == UserAgents.APP && !cleared } ?: passedAgent ?: own ?: UserAgents.browser
    val builder = request.newBuilder().header("User-Agent", agent)
    if (request.header("Referer") == null) builder.header("Referer", "${request.url.scheme}://${request.url.host}/")
    if (cookies.isNotEmpty()) builder.header("Cookie", cookies.joinToString("; ") { "${it.name}=${it.value}" })
    return builder.build()
}

/**
 * The site cookies and anti-bot passes the client holds, one share per [Route]: what was earned
 * along one route is never offered along the other.
 */
interface SitePasses {
    /** The cookies a request to [url] carries along [route]. */
    fun cookiesFor(route: Route, url: HttpUrl): List<Cookie>

    /** Takes the cookies [url]'s site set in an answer that came back along [route]. */
    fun saveFromResponse(route: Route, url: HttpUrl, cookies: List<Cookie>)

    /** The agent that passed the check of [host]'s site along [route], if one did. */
    fun userAgentFor(route: Route, host: String): String?

    /** Notes that the clearance [value] brought back an answer from [host]: the site took it. */
    fun clearanceWorked(route: Route, host: String, value: String)

    /** Forgets the clearance [value] that [host]'s site refused, and the agent with it once none is left. */
    fun dropClearance(route: Route, host: String, value: String)
}

/** Names the browser on a request that names no one, where OkHttp would put its own name. */
object DefaultUserAgent : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.header("User-Agent") != null) return chain.proceed(request)
        return chain.proceed(request.newBuilder().header("User-Agent", UserAgents.browser).build())
    }
}

/**
 * Puts [withSiteHeaders] and the route's cookies on every request, keeps the cookies the answer
 * sets for that route, and follows how the site treats a clearance: one that brings back an
 * answer has worked; one that still meets the check is forgotten, since it would go on being
 * carried in vain, under the browser's name. Without it, a Danbooru board is asked under the
 * app's name again. A network interceptor, so the client itself must keep no cookies.
 */
class SiteHeaders(private val passes: SitePasses) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url
        val host = url.host
        // Decided on the connection the request really takes, once OkHttp has picked it: a switch
        // flipped while the request was on its way cannot hand it what the other route earned.
        val route = Route.of(checkNotNull(chain.connection()) { "SiteHeaders runs as a network interceptor" })
        val cookies = passes.cookiesFor(route, url)
        val clearance = cookies.firstOrNull { it.name == Challenge.CLEARANCE_COOKIE }?.value
        val sent = withSiteHeaders(request, passes.userAgentFor(route, host), cleared = clearance != null, cookies)
        val response = chain.proceed(sent)
        passes.saveFromResponse(route, url, Cookie.parseAll(url, response.headers))
        DebugLog.d {
            "site $route $host/${url.pathSegments.firstOrNull().orEmpty()} ua=${sent.header("User-Agent")} cookies=${cookies.map { it.name }} " +
                "status=${response.code} cf-mitigated=${response.header("cf-mitigated")}"
        }
        when {
            clearance == null -> Unit
            response.header("cf-mitigated").equals("challenge", ignoreCase = true) -> {
                DebugLog.d { "clearance refused by $host ($route)" }
                passes.dropClearance(route, host, clearance)
            }
            response.code in 200..299 -> passes.clearanceWorked(route, host, clearance)
        }
        return response
    }
}

suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) = cont.resume(response) { _, _, _ -> response.close() }
        override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)
    })
}

/** GETs a page as text, turning the usual booru failure modes into readable messages. */
suspend fun OkHttpClient.getText(request: Request, json: Json): String = withContext(Dispatchers.IO) {
    val response = try {
        newCall(request).await()
    } catch (e: IOException) {
        throw BooruException(Problem.Code.NETWORK, e.message ?: e.javaClass.simpleName, e)
    }
    response.use {
        val body = it.body.utf8UpTo(MAX_TEXT_BODY) ?: throw BooruFormatException(Problem.Code.BAD_RESPONSE)
        if (isChallenge(it, body)) throw BooruChallengeException(request.url.toString())
        if (!it.isSuccessful) {
            // Danbooru and Moebooru explain refusals in a JSON body; that beats a status code.
            val detail = runCatching {
                json.parseToJsonElement(body).obj()?.let { o -> o.str("message") ?: o.str("reason") }
            }.getOrNull()
            if (detail != null && it.code in setOf(400, 403, 410, 422)) {
                throw BooruHttpException(it.code, Problem(Problem.Code.SITE_MESSAGE, detail))
            }
            val code = when (it.code) {
                401, 403 -> Problem.Code.ACCESS_DENIED
                404 -> Problem.Code.NOT_FOUND
                410 -> Problem.Code.GONE
                422 -> Problem.Code.INVALID_SEARCH
                429 -> Problem.Code.RATE_LIMITED
                in 500..599 -> Problem.Code.SERVER_DOWN
                else -> Problem.Code.HTTP_STATUS
            }
            throw BooruHttpException(it.code, Problem(code, it.code.toString()))
        }
        body
    }
}

/** The most a page or API answer may weigh: a list page is tens of kilobytes, never megabytes. */
const val MAX_TEXT_BODY = 8L shl 20

/** The body as text, or null when it is longer than [max] bytes: a site that big is not read. */
fun ResponseBody.utf8UpTo(max: Long): String? = bytesUpTo(max)?.decodeToString()

/**
 * The body as bytes, or null when it is longer than [max]: the body is read no further, so a
 * hostile or broken site cannot fill the memory with a single answer.
 */
fun ResponseBody.bytesUpTo(max: Long): ByteArray? {
    if (contentLength() > max) return null
    return source().use { source -> if (source.request(max + 1)) null else source.readByteArray() }
}

/** GETs JSON. Null for an empty body. */
suspend fun OkHttpClient.getJson(request: Request, json: Json): JsonElement? {
    val trimmed = getText(request, json).trim()
    if (trimmed.isEmpty()) return null
    if (trimmed.startsWith("<")) {
        throw BooruFormatException(Problem.Code.NOT_JSON)
    }
    if (trimmed.startsWith("\"")) {
        // Gelbooru forks answer errors as a bare JSON string.
        throw BooruHttpException(401, Problem(Problem.Code.SITE_MESSAGE, trimmed.trim('"').ifBlank { null }))
    }
    return try {
        json.parseToJsonElement(trimmed)
    } catch (e: Exception) {
        throw BooruFormatException(Problem.Code.BAD_RESPONSE, e)
    }
}

/**
 * Cloudflare marks its challenge pages with a header; older setups only show it in the page.
 * The page markers count only with a challenge status: ordinary pages carry the same
 * `challenge-platform` script when the site has JavaScript detections on.
 */
fun isChallenge(response: Response, body: String): Boolean =
    response.header("cf-mitigated").equals("challenge", ignoreCase = true) ||
        (response.code in CHALLENGE_CODES && body.trimStart().startsWith("<") &&
            (body.contains("<title>Just a moment", ignoreCase = true) || body.contains("challenge-platform", ignoreCase = true)))

private val CHALLENGE_CODES = setOf(403, 429, 503)

// Lenient accessors: booru APIs disagree on types (numbers as strings, "" for null, 0/1 booleans).

fun JsonElement?.obj(): JsonObject? = this as? JsonObject
fun JsonElement?.arr(): JsonArray? = this as? JsonArray

fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull?.takeIf { it.isNotBlank() }

fun JsonObject.long(key: String): Long? {
    val p = this[key] as? JsonPrimitive ?: return null
    if (p is JsonNull) return null
    return p.longOrNull ?: p.doubleOrNull?.toLong() ?: p.contentOrNull?.trim()?.toLongOrNull()
}

fun JsonObject.int(key: String): Int? = long(key)?.toInt()

fun JsonObject.bool(key: String): Boolean? {
    val p = this[key] as? JsonPrimitive ?: return null
    return p.booleanOrNull ?: p.longOrNull?.let { it != 0L } ?: p.contentOrNull?.let { it == "true" || it == "1" }
}

fun String.extensionFromUrl(): String? =
    substringBefore('?').substringAfterLast('/').substringAfterLast('.', "").lowercase().takeIf { it.length in 2..5 }
