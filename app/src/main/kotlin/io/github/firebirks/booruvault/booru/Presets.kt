package io.github.firebirks.booruvault.booru

/** Why a preset deserves a word before it is added; the interface says it in the user's language. */
enum class PresetNote { SAFE_ONLY, TWO_TAG_LIMIT, WEB_WITHOUT_KEY }

data class SourcePreset(val source: BooruSource, val note: PresetNote? = null)

/**
 * Sites offered by name: the ones meant to be safe, plus the large general boards. Sites given
 * over to adult content are not listed; any site can still be added by its address.
 */
object Presets {
    val all = listOf(
        SourcePreset(BooruSource("safebooru-donmai", "Safebooru", BooruEngine.DANBOORU, "https://safebooru.donmai.us"), PresetNote.SAFE_ONLY),
        SourcePreset(BooruSource("danbooru", "Danbooru", BooruEngine.DANBOORU, "https://danbooru.donmai.us"), PresetNote.TWO_TAG_LIMIT),
        SourcePreset(BooruSource("konachan-net", "Konachan.net", BooruEngine.MOEBOORU, "https://konachan.net"), PresetNote.SAFE_ONLY),
        SourcePreset(BooruSource("safebooru-org", "Safebooru.org", BooruEngine.GELBOORU, "https://safebooru.org"), PresetNote.SAFE_ONLY),
        SourcePreset(BooruSource("gelbooru", "Gelbooru", BooruEngine.GELBOORU, "https://gelbooru.com"), PresetNote.WEB_WITHOUT_KEY),
        SourcePreset(BooruSource("e926", "e926", BooruEngine.E621, "https://e926.net"), PresetNote.SAFE_ONLY),
    )

    /** What a fresh install starts with: sites that work without an account. */
    val defaults: List<BooruSource> = listOf("safebooru-donmai", "danbooru", "konachan-net", "safebooru-org", "e926")
        .map { id -> all.first { it.source.id == id }.source }
}
