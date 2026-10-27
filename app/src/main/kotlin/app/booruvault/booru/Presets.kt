package app.booruvault.booru

/** Why a preset deserves a word before it is added; the interface says it in the user's language. */
enum class PresetNote { SAFE_ONLY, TWO_TAG_LIMIT, WEB_WITHOUT_KEY, WEB_WITHOUT_KEY_CHALLENGE }

data class SourcePreset(val source: BooruSource, val note: PresetNote? = null)

object Presets {
    val all = listOf(
        SourcePreset(BooruSource("safebooru-donmai", "Safebooru", BooruEngine.DANBOORU, "https://safebooru.donmai.us"), PresetNote.SAFE_ONLY),
        SourcePreset(BooruSource("danbooru", "Danbooru", BooruEngine.DANBOORU, "https://danbooru.donmai.us"), PresetNote.TWO_TAG_LIMIT),
        SourcePreset(BooruSource("yandere", "Yande.re", BooruEngine.MOEBOORU, "https://yande.re")),
        SourcePreset(BooruSource("konachan-net", "Konachan.net", BooruEngine.MOEBOORU, "https://konachan.net"), PresetNote.SAFE_ONLY),
        SourcePreset(BooruSource("konachan-com", "Konachan.com", BooruEngine.MOEBOORU, "https://konachan.com")),
        SourcePreset(BooruSource("safebooru-org", "Safebooru.org", BooruEngine.GELBOORU, "https://safebooru.org"), PresetNote.SAFE_ONLY),
        SourcePreset(BooruSource("gelbooru", "Gelbooru", BooruEngine.GELBOORU, "https://gelbooru.com"), PresetNote.WEB_WITHOUT_KEY),
        SourcePreset(BooruSource("rule34", "Rule34.xxx", BooruEngine.GELBOORU, "https://api.rule34.xxx"), PresetNote.WEB_WITHOUT_KEY_CHALLENGE),
        SourcePreset(BooruSource("xbooru", "Xbooru", BooruEngine.GELBOORU, "https://xbooru.com")),
        SourcePreset(BooruSource("tbib", "TBIB", BooruEngine.GELBOORU, "https://tbib.org")),
        SourcePreset(BooruSource("e926", "e926", BooruEngine.E621, "https://e926.net"), PresetNote.SAFE_ONLY),
        SourcePreset(BooruSource("e621", "e621", BooruEngine.E621, "https://e621.net")),
    )

    /** What a fresh install starts with: sites that work without an account. */
    val defaults: List<BooruSource> = listOf("safebooru-donmai", "danbooru", "yandere", "konachan-net", "safebooru-org", "e926")
        .map { id -> all.first { it.source.id == id }.source }
}
