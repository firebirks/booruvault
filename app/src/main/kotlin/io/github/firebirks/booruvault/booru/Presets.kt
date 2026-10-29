package io.github.firebirks.booruvault.booru

/** Why a preset deserves a word before it is added; the interface says it in the user's language. */
enum class PresetNote { SAFE_ONLY }

data class SourcePreset(val source: BooruSource, val note: PresetNote? = null)

/**
 * Sites offered by name: one board meant to be safe for each engine with an API, so the list shows
 * every engine at work and is safe by what it names rather than by a filter. Any other site, the
 * large general boards included, is added from its address.
 */
object Presets {
    val all = listOf(
        SourcePreset(BooruSource("safebooru-donmai", "Safebooru", BooruEngine.DANBOORU, "https://safebooru.donmai.us"), PresetNote.SAFE_ONLY),
        SourcePreset(BooruSource("konachan-net", "Konachan.net", BooruEngine.MOEBOORU, "https://konachan.net"), PresetNote.SAFE_ONLY),
        SourcePreset(BooruSource("safebooru-org", "Safebooru.org", BooruEngine.GELBOORU, "https://safebooru.org"), PresetNote.SAFE_ONLY),
        SourcePreset(BooruSource("e926", "e926", BooruEngine.E621, "https://e926.net"), PresetNote.SAFE_ONLY),
    )

    /** What a fresh install starts with: every preset, as none needs an account. */
    val defaults: List<BooruSource> = all.map { it.source }
}
