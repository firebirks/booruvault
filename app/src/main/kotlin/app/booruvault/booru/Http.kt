package app.booruvault.booru

import app.booruvault.util.Problem
import kotlinx.coroutines.suspendCancellableCoroutine
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
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

const val USER_AGENT = "BooruVault/0.1 (Android)"

suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) = cont.resume(response) { _, _, _ -> response.close() }
        override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)
    })
}

/** GETs a page as text, turning the usual booru failure modes into readable messages. */
suspend fun OkHttpClient.getText(request: Request, json: Json): String {
    val response = try {
        newCall(request).await()
    } catch (e: IOException) {
        throw BooruException(Problem.Code.NETWORK, e.message ?: e.javaClass.simpleName, e)
    }
    response.use {
        val body = it.body.string()
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
        return body
    }
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

/** Cloudflare marks its challenge pages with a header; older setups only show it in the page. */
fun isChallenge(response: Response, body: String): Boolean =
    response.header("cf-mitigated").equals("challenge", ignoreCase = true) ||
        (response.code in setOf(403, 429, 503) && body.contains("<title>Just a moment", ignoreCase = true)) ||
        (body.contains("challenge-platform", ignoreCase = true) && body.trimStart().startsWith("<"))

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
