package io.github.firebirks.booruvault.booru

import io.github.firebirks.booruvault.BuildConfig
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
     * The name for [source]'s site. e621 asks API clients to say who they are, so for it the
     * app's name rides along at the end, and the account's when the request is signed with it.
     */
    fun forSource(source: BooruSource?, webView: Boolean = false): String {
        val base = if (webView) this.webView else browser
        if (source?.engine != BooruEngine.E621) return base
        val account = if (source.hasCredentials) " (by ${source.login.trim()} on e621)" else ""
        return "$base BooruVault/${BuildConfig.VERSION_NAME}$account"
    }

    const val DEFAULT_CHROME = "130"
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
