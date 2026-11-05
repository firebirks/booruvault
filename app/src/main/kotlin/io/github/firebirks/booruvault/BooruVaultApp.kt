package io.github.firebirks.booruvault

import android.app.Application
import android.content.res.Configuration
import io.github.firebirks.booruvault.settings.AppLanguage
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import io.github.firebirks.booruvault.util.LibraryBuffers

class BooruVaultApp : Application(), SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    init {
        // Before anything of the app runs, so before any JSON goes through the library.
        LibraryBuffers.keepNoJsonBuffers()
    }

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // The system has just set the default locale back to the phone's; the app's language holds.
        AppLanguage.applyDefault(this)
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader = container.imageLoader
}
