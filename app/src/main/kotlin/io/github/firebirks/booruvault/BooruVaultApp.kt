package io.github.firebirks.booruvault

import android.app.Application
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

    override fun newImageLoader(context: PlatformContext): ImageLoader = container.imageLoader
}
