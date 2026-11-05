package io.github.firebirks.booruvault.ui.common

import android.graphics.Color
import android.net.Uri
import android.view.View
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.unit.Dp
import androidx.media3.common.AudioAttributes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import io.github.firebirks.booruvault.R

/**
 * Whether videos play silently: they start so, since a viewer is swiped through in places where
 * sound is not wanted, and the choice made on one holds for the rest of the session.
 */
private var videosMuted by mutableStateOf(true)

/**
 * A looping player that lives exactly as long as it is on screen and pauses in the background.
 * It starts muted, with a button that turns the sound on, and stays as last set.
 */
@OptIn(UnstableApi::class)
@kotlin.OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun VideoPlayer(
    uri: Uri,
    dataSourceFactory: DataSource.Factory,
    modifier: Modifier = Modifier,
    /** Told when a tap shows or hides the player's controls, so a viewer can follow with its own. */
    onControlsVisible: ((Boolean) -> Unit)? = null,
    /** How far from the top the sound button sits: under the viewer's own bar, whose height the viewer measures. */
    soundButtonTop: Dp? = null,
) {
    val context = LocalContext.current
    val player = remember(uri) {
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            // With the sound on, other audio pauses for it, and unplugged headphones pause it.
            .setAudioAttributes(AudioAttributes.DEFAULT, true)
            .setHandleAudioBecomingNoisy(true)
            .build()
            .apply {
                setMediaItem(MediaItem.fromUri(uri))
                repeatMode = Player.REPEAT_MODE_ONE
                volume = if (videosMuted) 0f else 1f
                playWhenReady = true
                prepare()
            }
    }
    DisposableEffect(player) { onDispose { player.release() } }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, player) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) player.pause() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    Box(modifier) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    setBackgroundColor(Color.BLACK)
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                    onControlsVisible?.let { listener ->
                        setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility -> listener(visibility == View.VISIBLE) })
                    }
                    this.player = player
                }
            },
            update = { it.player = player },
            onRelease = { it.player = null },
            modifier = Modifier.fillMaxSize(),
        )
        IconButton(
            onClick = {
                videosMuted = !videosMuted
                player.volume = if (videosMuted) 0f else 1f
            },
            colors = IconButtonDefaults.iconButtonColors(containerColor = ComposeColor.Black.copy(alpha = 0.45f), contentColor = ComposeColor.White),
            // Under the viewer's own bar and clear of the player's, whose seek bar and settings sit
            // at the bottom. The status bar counts whether shown or hidden, so the button stays put.
            modifier = Modifier.align(Alignment.TopEnd)
                .then(if (soundButtonTop != null) Modifier.padding(top = soundButtonTop) else Modifier.windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility).padding(top = 64.dp))
                .padding(end = 12.dp),
        ) {
            Icon(
                if (videosMuted) Icons.AutoMirrored.Outlined.VolumeOff else Icons.AutoMirrored.Outlined.VolumeUp,
                stringResource(if (videosMuted) R.string.video_unmute else R.string.video_mute),
            )
        }
    }
}
