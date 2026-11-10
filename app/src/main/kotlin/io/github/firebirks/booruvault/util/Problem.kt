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
        /** The connection or the answer took too long: over Tor, most often a slow circuit. */
        TIMEOUT,
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
        /** The phone's storage refused a write: full, or failing. */
        STORAGE_WRITE,
        /** A file picked on the device could not be opened or read. */
        FILE_UNREADABLE,
        /** The settings file could not be read: the settings start afresh, the file kept aside. */
        SETTINGS_UNREADABLE,
        /** A file picked to restore the vault from is not a backup, or is cut short or damaged. */
        BACKUP_UNREADABLE,
        /** A backup in a format this version of the app does not know: a later one made it. */
        BACKUP_NEWER,
        /** The password was changed while a backup was being made: its files no longer match its header. */
        BACKUP_KEY_CHANGED,
        UNKNOWN,
    }
}

/** Whether a request failed for want of Tor: nothing to retry until Orbot answers and its exit is verified. */
fun Problem.blockedByTor(): Boolean = when (code) {
    Problem.Code.TOR_UNVERIFIED, Problem.Code.TOR_UNREACHABLE, Problem.Code.TOR_DNS_BLOCKED -> true
    else -> false
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
 * reported as Tor being down, and one that is a timeout as a timeout.
 */
fun Throwable.toProblem(): Problem {
    val chain = generateSequence(this) { it.cause }.take(8).toList()
    val known = chain.mapNotNull { (it as? HasProblem)?.problem }
    known.firstOrNull { it.code != Problem.Code.NETWORK }?.let { return it }
    if (chain.any { it is java.net.SocketTimeoutException }) return Problem(Problem.Code.TIMEOUT)
    known.firstOrNull()?.let { return it }
    val detail = message ?: javaClass.simpleName
    return Problem(if (this is java.io.IOException) Problem.Code.NETWORK else Problem.Code.UNKNOWN, detail)
}
