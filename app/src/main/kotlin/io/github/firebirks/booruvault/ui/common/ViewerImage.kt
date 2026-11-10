package io.github.firebirks.booruvault.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.network.HttpException
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.NullRequestDataException
import io.github.firebirks.booruvault.R
import io.github.firebirks.booruvault.util.HasProblem
import io.github.firebirks.booruvault.util.Problem
import io.github.firebirks.booruvault.util.toProblem

/**
 * The load of a viewer's full image: the [request] to show, what the last try [failure]d with,
 * and a way to [retry] it.
 */
class ViewerImageLoad internal constructor(val request: ImageRequest, val failure: Throwable?, val retry: () -> Unit)

/**
 * The request for [data] as a viewer's full image, asked under [userAgent] when one is given. A
 * zoomable image says when it is displayed and nothing when its load fails, which would leave the
 * viewer waiting for good: the request carries a listener of its own for that. A new try is a new
 * request, which the image takes as a new model and loads again.
 */
@Composable
fun rememberViewerImageLoad(data: Any?, userAgent: String? = null): ViewerImageLoad {
    val context = LocalContext.current
    var attempt by remember(data) { mutableIntStateOf(0) }
    var failure by remember(data) { mutableStateOf<Throwable?>(null) }
    val request = remember(data, userAgent, attempt) {
        ImageRequest.Builder(context).data(data)
            // Named as the source's other requests are: an original may be refused to the browser's name.
            .apply { if (userAgent != null) httpHeaders(NetworkHeaders.Builder().set("User-Agent", userAgent).build()) }
            .listener(onStart = { failure = null }, onError = { _, result -> failure = result.throwable })
            .build()
    }
    return ViewerImageLoad(request, failure) {
        failure = null
        attempt++
    }
}

/**
 * What a site's image that failed to load stands for. A check that guards the file itself is
 * passed at the file's own address, so the problem carries [url], as a download's does. With no
 * [url] the post has no file to show at all.
 */
internal fun Throwable.remoteImageProblem(url: String?): Problem {
    if (url == null || this is NullRequestDataException) return Problem(Problem.Code.NO_FILE)
    val refused = generateSequence(this) { it.cause }.take(8).firstNotNullOfOrNull { it as? HttpException }?.response
        ?: return toProblem()
    val status = refused.code.toString()
    return when {
        refused.headers["cf-mitigated"].equals("challenge", ignoreCase = true) -> Problem(Problem.Code.CHALLENGE, url)
        refused.code == 429 -> Problem(Problem.Code.RATE_LIMITED)
        refused.code in 500..599 -> Problem(Problem.Code.SERVER_DOWN, status)
        else -> Problem(Problem.Code.HTTP_STATUS, status)
    }
}

/** What a vault file that failed to show stands for: the vault's own reason when it gave one, otherwise a file that cannot be read. */
internal fun Throwable.vaultImageProblem(): Problem =
    generateSequence(this) { it.cause }.take(8).firstNotNullOfOrNull { (it as? HasProblem)?.problem }
        ?: Problem(Problem.Code.FILE_UNREADABLE, message ?: javaClass.simpleName)

/**
 * Over a viewer's page that could not be shown: what went wrong and the ways on, [onVerify] where
 * a check can be passed in the built-in browser. The buttons are filled, so they read over
 * whatever the page shows beneath them, in either theme. The box is there with or without a
 * [problem]: a text that appears inside a live region that is always there is announced.
 */
@Composable
fun ViewerLoadError(problem: Problem?, onRetry: (() -> Unit)?, onVerify: (() -> Unit)?, modifier: Modifier = Modifier) {
    Box(modifier.semantics { liveRegion = LiveRegionMode.Polite }, contentAlignment = Alignment.Center) {
        if (problem == null) return@Box
        Column(
            Modifier.padding(24.dp).widthIn(max = 360.dp).background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(12.dp)).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(problemText(problem), color = Color.White, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            if (onVerify != null) Button(onClick = onVerify) { Text(stringResource(R.string.action_verify_in_browser)) }
            if (onRetry != null) FilledTonalButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
        }
    }
}
