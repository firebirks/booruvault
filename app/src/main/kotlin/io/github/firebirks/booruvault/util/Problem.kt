package io.github.firebirks.booruvault.util

import kotlinx.serialization.Serializable

/**
 * Something that went wrong, as a code the interface turns into text in the user's language.
 * [detail] carries what cannot be translated: an HTTP status, an address, a site's own message.
 */
@Serializable
data class Problem(val code: Code, val detail: String? = null) {
    @Serializable
    enum class Code {
        NETWORK,
        TOR_UNREACHABLE,
        TOR_DNS_BLOCKED,
        TOR_UNVERIFIED,
        CHALLENGE,
        CHALLENGE_PASS_REFUSED,
        CHALLENGE_PASS_REFUSED_TOR,
        ACCESS_DENIED,
        NOT_FOUND,
        GONE,
        INVALID_SEARCH,
        RATE_LIMITED,
        SERVER_DOWN,
        HTTP_STATUS,
        SITE_MESSAGE,
        NOT_JSON,
        BAD_RESPONSE,
        BAD_URL,
        NO_IMAGE_ON_PAGE,
        UNRECOGNISED_PAGES,
        NO_FILE,
        TOO_LARGE,
        ALREADY_IN_VAULT,
        NO_VAULT,
        VAULT_LOCKED,
        FOLDER_UNAVAILABLE,
        NOT_THROUGH_TOR,
        UNKNOWN,
    }
}

/** An exception that knows which [Problem] it stands for. */
interface HasProblem {
    val problem: Problem
}

open class ProblemException(final override val problem: Problem, cause: Throwable? = null) :
    java.io.IOException(problem.detail ?: problem.code.name, cause), HasProblem {
    constructor(code: Problem.Code, detail: String? = null) : this(Problem(code, detail))
}

/**
 * The most telling problem along the cause chain: a network error caused by Tor being down is
 * reported as Tor being down.
 */
fun Throwable.toProblem(): Problem {
    val known = generateSequence(this) { it.cause }.take(8).mapNotNull { (it as? HasProblem)?.problem }.toList()
    known.firstOrNull { it.code != Problem.Code.NETWORK }?.let { return it }
    known.firstOrNull()?.let { return it }
    val detail = message ?: javaClass.simpleName
    return Problem(if (this is java.io.IOException) Problem.Code.NETWORK else Problem.Code.UNKNOWN, detail)
}
