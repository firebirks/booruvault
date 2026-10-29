package io.github.firebirks.booruvault.util

import android.util.Log
import io.github.firebirks.booruvault.BuildConfig

/** TEMPORARY: traces of the anti-bot check for testing on the phone, debug builds only. Remove before committing. */
object DebugLog {
    fun d(message: () -> String) {
        // A JVM test has no Log to write to.
        if (BuildConfig.DEBUG) runCatching { Log.d("BooruVaultCheck", message()) }
    }
}
