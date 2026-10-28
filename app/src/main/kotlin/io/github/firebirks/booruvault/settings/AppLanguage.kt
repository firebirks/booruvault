package io.github.firebirks.booruvault.settings

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * The language the app shows, chosen in its settings or left to the phone.
 *
 * On Android 13 and later the choice is the system's own per-app language, so the two settings
 * pages agree and the system restarts the activity on a change. Before that, the choice is kept
 * in a plain preference, which has to be readable before the vault opens and says nothing but a
 * language, and is applied to every context that shows text: the activity and the download
 * service's notifications.
 */
object AppLanguage {
    /** A language the app is translated to, by BCP 47 tag, with its own name for the picker. */
    class Choice(val tag: String, val name: String)

    val choices = listOf(
        Choice("en", "English"),
        Choice("ar", "العربية"),
        Choice("de", "Deutsch"),
        Choice("es", "Español"),
        Choice("fr", "Français"),
        Choice("hi", "हिन्दी"),
        Choice("id", "Bahasa Indonesia"),
        Choice("it", "Italiano"),
        Choice("ja", "日本語"),
        Choice("ko", "한국어"),
        Choice("nl", "Nederlands"),
        Choice("pl", "Polski"),
        Choice("pt", "Português"),
        Choice("ru", "Русский"),
        Choice("th", "ไทย"),
        Choice("tr", "Türkçe"),
        Choice("uk", "Українська"),
        Choice("vi", "Tiếng Việt"),
        Choice("zh-Hans", "简体中文"),
        Choice("zh-Hant", "繁體中文"),
    )

    /** The chosen tag, or null for the phone's language. */
    fun current(context: Context): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val locales = context.getSystemService(LocaleManager::class.java).applicationLocales
            if (locales.isEmpty) return null
            val chosen = locales[0]
            return choices.firstOrNull { it.tag.equals(chosen.toLanguageTag(), ignoreCase = true) }?.tag
                ?: choices.firstOrNull { Locale.forLanguageTag(it.tag).language == chosen.language }?.tag
        }
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
    }

    /**
     * Sets the language, null for the phone's. Returns true when the caller has to recreate its
     * activity for the change to show; on Android 13 and later the system does that itself.
     */
    fun set(context: Context, tag: String?): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java).applicationLocales =
                if (tag == null) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
            return false
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, tag).apply()
        return true
    }

    /** [base] with the chosen language, for attachBaseContext; [base] itself when there is none. */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        val tag = current(base) ?: return base
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration).apply { setLocale(locale) }
        return base.createConfigurationContext(config)
    }

    private const val PREFS = "ui"
    private const val KEY = "language"
}
