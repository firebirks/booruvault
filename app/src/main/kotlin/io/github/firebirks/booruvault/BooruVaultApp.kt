package io.github.firebirks.booruvault

import android.app.Application
import android.content.res.Configuration
import android.os.Build
import io.github.firebirks.booruvault.settings.AppLanguage
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import io.github.firebirks.booruvault.util.LibraryBuffers
import java.io.File

class BooruVaultApp : Application(), SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    // Whether this is the app's own process, and not one of the engine's: see onCreate.
    private var main = false

    init {
        // Before anything of the app runs, so before any JSON goes through the library.
        LibraryBuffers.keepNoJsonBuffers()
    }

    override fun onCreate() {
        super.onCreate()
        // The built-in browsers' engine runs its pages in processes of its own, and Android starts
        // the application in each of them: only the app's own process is the app. In the others
        // nothing of it is made, so none of them checks Tor's exit or sweeps the vault's folder.
        // The pages' processes are isolated ones besides, with no files of the app to read.
        main = inMainProcess()
        if (!main) return
        container = AppContainer(this)
    }

    private fun inMainProcess(): Boolean {
        val name = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getProcessName()
        } else {
            runCatching { File("/proc/self/cmdline").readText().substringBefore('\u0000').trim() }.getOrNull()
        }
        return name == null || name == packageName
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // The system has just set the default locale back to the phone's; the app's language holds.
        // It is read from the app's files, which the engine's isolated processes cannot open.
        if (main) AppLanguage.applyDefault(this)
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader = container.imageLoader
}
