package io.github.firebirks.booruvault.ui.common

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Light status-bar icons over a black viewer, and optionally no system bars at all. Set again
 * after a change of dark mode or orientation, which the activity lives through and may have
 * set the icons up anew for.
 */
@Composable
fun ViewerSystemBars(hidden: Boolean) {
    val view = LocalView.current
    val configuration = LocalConfiguration.current
    DisposableEffect(hidden, configuration.uiMode, configuration.orientation) {
        val window = view.context.findActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.isAppearanceLightStatusBars = false
        controller?.isAppearanceLightNavigationBars = false
        if (hidden) {
            controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            // Back to the theme's own: dark icons over a light theme, light over a dark one, as
            // the theme stands now. What was found on entry would be stale after a change of
            // dark mode the viewer lived through.
            val night = (view.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            controller?.isAppearanceLightStatusBars = !night
            controller?.isAppearanceLightNavigationBars = !night
        }
    }
}
