package io.github.firebirks.booruvault.ui.common

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import io.github.firebirks.booruvault.R
import io.github.firebirks.booruvault.booru.BooruEngine
import io.github.firebirks.booruvault.booru.PresetNote
import io.github.firebirks.booruvault.booru.Rating
import io.github.firebirks.booruvault.booru.TagCategory
import io.github.firebirks.booruvault.settings.GridSize
import io.github.firebirks.booruvault.util.Problem

/** Text decided away from the screen (a view model, a callback) and put into words on it. */
sealed interface UiText {
    data class Res(@param:StringRes val id: Int, val args: List<Any> = emptyList()) : UiText
    data class Plural(@param:PluralsRes val id: Int, val count: Int, val args: List<Any> = listOf(count)) : UiText
    data class Of(val problem: Problem) : UiText
    data class Join(val parts: List<UiText>, val separator: String = " · ") : UiText
}

fun UiText.resolve(context: Context): String = when (this) {
    is UiText.Res -> context.getString(id, *args.resolved(context))
    is UiText.Plural -> context.resources.getQuantityString(id, count, *args.resolved(context))
    is UiText.Of -> context.problemText(problem)
    is UiText.Join -> parts.joinToString(separator) { it.resolve(context) }
}

/** Arguments that are themselves [UiText] are put into words first, in the same language. */
private fun List<Any>.resolved(context: Context): Array<Any> = map { if (it is UiText) it.resolve(context) else it }.toTypedArray()

@Composable
fun UiText.text(): String {
    // Read so that a change of language recomposes.
    LocalConfiguration.current
    return resolve(LocalContext.current)
}

@Composable
fun problemText(problem: Problem): String {
    LocalConfiguration.current
    return LocalContext.current.problemText(problem)
}

fun Context.problemText(problem: Problem): String {
    val detail = problem.detail.orEmpty()
    return when (problem.code) {
        Problem.Code.NETWORK -> getString(R.string.problem_network, detail)
        Problem.Code.TOR_UNREACHABLE -> getString(R.string.problem_tor_unreachable)
        Problem.Code.TOR_DNS_BLOCKED -> getString(R.string.problem_tor_dns_blocked)
        Problem.Code.TOR_UNVERIFIED -> getString(R.string.problem_tor_unverified)
        Problem.Code.CHALLENGE -> getString(R.string.problem_challenge)
        Problem.Code.ACCESS_DENIED -> getString(R.string.problem_access_denied, detail)
        Problem.Code.NOT_FOUND -> getString(R.string.problem_not_found)
        Problem.Code.GONE -> getString(R.string.problem_gone)
        Problem.Code.INVALID_SEARCH -> getString(R.string.problem_invalid_search)
        Problem.Code.RATE_LIMITED -> getString(R.string.problem_rate_limited)
        Problem.Code.SERVER_DOWN -> getString(R.string.problem_server_down, detail)
        Problem.Code.HTTP_STATUS -> getString(R.string.problem_http_status, detail)
        // The site's own words, in the site's language, are more useful than any paraphrase.
        Problem.Code.SITE_MESSAGE -> problem.detail ?: getString(R.string.problem_site_message)
        Problem.Code.NOT_JSON -> getString(R.string.problem_not_json)
        Problem.Code.BAD_RESPONSE -> getString(R.string.problem_bad_response)
        Problem.Code.BAD_URL -> getString(R.string.problem_bad_url, detail)
        Problem.Code.NO_IMAGE_ON_PAGE -> getString(R.string.problem_no_image_on_page)
        Problem.Code.UNRECOGNISED_PAGES -> getString(R.string.problem_unrecognised_pages)
        Problem.Code.NO_FILE -> getString(R.string.problem_no_file)
        Problem.Code.TOO_LARGE -> getString(R.string.problem_too_large)
        Problem.Code.ALREADY_IN_VAULT -> getString(R.string.problem_already_in_vault)
        Problem.Code.NO_VAULT -> getString(R.string.problem_no_vault)
        Problem.Code.VAULT_LOCKED -> getString(R.string.problem_vault_locked)
        Problem.Code.FOLDER_UNAVAILABLE -> getString(R.string.problem_folder_unavailable)
        Problem.Code.NOT_THROUGH_TOR -> getString(R.string.problem_not_through_tor)
        Problem.Code.UNKNOWN -> getString(R.string.problem_unknown, detail)
    }
}

@StringRes
fun Rating.labelRes(): Int = when (this) {
    Rating.GENERAL -> R.string.rating_general
    Rating.SENSITIVE -> R.string.rating_sensitive
    Rating.QUESTIONABLE -> R.string.rating_questionable
    Rating.EXPLICIT -> R.string.rating_explicit
    Rating.UNKNOWN -> R.string.rating_unknown
}

@StringRes
fun BooruEngine.labelRes(): Int = when (this) {
    BooruEngine.DANBOORU -> R.string.engine_danbooru
    BooruEngine.MOEBOORU -> R.string.engine_moebooru
    BooruEngine.GELBOORU -> R.string.engine_gelbooru
    BooruEngine.E621 -> R.string.engine_e621
    BooruEngine.GELBOORU_HTML -> R.string.engine_gelbooru_html
    BooruEngine.SHIMMIE -> R.string.engine_shimmie
    BooruEngine.HTML_GENERIC -> R.string.engine_html_generic
}

@StringRes
fun GridSize.labelRes(): Int = when (this) {
    GridSize.SMALL -> R.string.grid_small
    GridSize.MEDIUM -> R.string.grid_medium
    GridSize.LARGE -> R.string.grid_large
}

@StringRes
fun TagCategory.labelRes(): Int = when (this) {
    TagCategory.ARTIST -> R.string.tag_category_artist
    TagCategory.COPYRIGHT -> R.string.tag_category_copyright
    TagCategory.CHARACTER -> R.string.tag_category_character
    TagCategory.SPECIES -> R.string.tag_category_species
    TagCategory.GENERAL -> R.string.tag_category_general
    TagCategory.META -> R.string.tag_category_meta
    TagCategory.OTHER -> R.string.tag_category_other
}

@StringRes
fun PresetNote.labelRes(): Int = when (this) {
    PresetNote.SAFE_ONLY -> R.string.preset_note_safe_only
}
