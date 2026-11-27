package io.github.firebirks.booruvault.util

import android.content.Context
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageResult
import io.github.firebirks.booruvault.BuildConfig
import java.io.File

/**
 * For trying a debug build where what a site shows should stay off the screen: with the file
 * `no-images` in the app's files, the app shows no image at all, in the grid, the viewer, the
 * downloads' previews or the vault, and the built-in browser loads none. A release build never
 * looks. Set and cleared from a computer:
 * `adb shell run-as io.github.firebirks.booruvault.debug touch files/no-images` (or `rm`).
 */
fun debugNoImages(context: Context): Boolean = BuildConfig.DEBUG && File(context.filesDir, "no-images").exists()

/** Answers every image request with a failure, before anything is fetched or read. */
object NoImages : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult =
        ErrorResult(image = null, request = chain.request, throwable = IllegalStateException("images are off in this debug build"))
}
