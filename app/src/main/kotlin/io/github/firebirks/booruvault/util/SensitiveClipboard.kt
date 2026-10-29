package io.github.firebirks.booruvault.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Links and tags the user copies, marked sensitive so the clipboard preview does not show them,
 * and taken off the clipboard again when the vault locks. An app in the background cannot read
 * the clipboard to tell whether the text is still its own, so whatever was copied after it, in
 * any app, goes as well.
 */
class SensitiveClipboard(private val put: (label: String, text: String) -> Unit, private val clear: () -> Unit) {
    private val held = AtomicBoolean(false)

    fun copy(label: String, text: String) {
        put(label, text)
        held.set(true)
    }

    /** Clears the clipboard if the app put something on it since it last did. */
    fun forget() {
        if (held.getAndSet(false)) clear()
    }

    companion object {
        fun of(context: Context): SensitiveClipboard {
            val manager = context.getSystemService(ClipboardManager::class.java)
            return SensitiveClipboard(
                put = { label, text ->
                    val clip = ClipData.newPlainText(label, text).apply {
                        description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
                    }
                    manager?.setPrimaryClip(clip)
                },
                clear = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) manager?.clearPrimaryClip() else manager?.setPrimaryClip(ClipData.newPlainText("", ""))
                },
            )
        }
    }
}
