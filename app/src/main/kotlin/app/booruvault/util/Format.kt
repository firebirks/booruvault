package app.booruvault.util

import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** Whether Google Play services are on the phone; the app itself never needs them. */
fun hasGooglePlayServices(context: android.content.Context): Boolean = try {
    context.packageManager.getPackageInfo("com.google.android.gms", 0)
    true
} catch (_: android.content.pm.PackageManager.NameNotFoundException) {
    false
}

object Mime {
    private val byExtension = mapOf(
        "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png", "gif" to "image/gif",
        "webp" to "image/webp", "avif" to "image/avif", "bmp" to "image/bmp", "heic" to "image/heic",
        "mp4" to "video/mp4", "m4v" to "video/mp4", "webm" to "video/webm", "mov" to "video/quicktime",
        "mkv" to "video/x-matroska", "zip" to "application/zip",
    )

    fun fromExtension(ext: String?): String = byExtension[ext?.lowercase()] ?: "application/octet-stream"

    fun extensionFor(mime: String): String? = byExtension.entries.firstOrNull { it.value == mime }?.key
}

/** Sizes, dates and counts in the conventions of the phone's language. */
fun formatBytes(context: android.content.Context, bytes: Long): String =
    android.text.format.Formatter.formatShortFileSize(context, bytes)

fun formatDate(millis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.getDefault()).format(Date(millis))

fun formatCount(n: Long): String =
    android.icu.text.CompactDecimalFormat.getInstance(Locale.getDefault(), android.icu.text.CompactDecimalFormat.CompactStyle.SHORT).format(n)

/** Keeps names safe for any file system a user might export to. */
fun safeFileName(name: String): String =
    name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").replace(Regex("\\s+"), "_").take(120)
