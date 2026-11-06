package io.github.firebirks.booruvault.ui.browse

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import io.github.firebirks.booruvault.R
import io.github.firebirks.booruvault.net.TorState
import io.github.firebirks.booruvault.net.TorStatus
import io.github.firebirks.booruvault.ui.common.OnionIcon
import io.github.firebirks.booruvault.ui.common.OnionOffIcon

/** What the onion in Browse's top bar says about the route. */
enum class TorBadge { OFF, CHECKING, VERIFIED, FAILED }

/**
 * The route at a glance, as the settings' verdict says it: Tor off; its exit verified; Orbot
 * answering and the exit being checked, while nothing loads yet; or not working, with Orbot
 * silent or missing or an exit check that failed, so that nothing goes out at all.
 */
fun torBadge(state: TorState): TorBadge = when {
    !state.enabled -> TorBadge.OFF
    state.verifiedExit != null -> TorBadge.VERIFIED
    state.verifyError != null -> TorBadge.FAILED
    state.status == TorStatus.OFF || state.status == TorStatus.NOT_INSTALLED -> TorBadge.FAILED
    else -> TorBadge.CHECKING
}

/** The onion: purple when verified, yellow while checking, red when not working, grey and struck through when off. */
@Composable
fun TorBadgeButton(state: TorState, onClick: () -> Unit) {
    val badge = torBadge(state)
    val dark = isSystemInDarkTheme()
    val tint = when (badge) {
        TorBadge.VERIFIED -> if (dark) Color(0xFFCDA8FF) else Color(0xFF6B3FA0)
        TorBadge.CHECKING -> if (dark) Color(0xFFFFD54F) else Color(0xFF8D6E00)
        // Not the theme's error colour: on a dark theme that is a pale salmon, which does not read as an alarm.
        TorBadge.FAILED -> if (dark) Color(0xFFFF5252) else Color(0xFFC62828)
        TorBadge.OFF -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val label = when (badge) {
        TorBadge.VERIFIED -> R.string.tor_badge_verified
        TorBadge.CHECKING -> R.string.tor_badge_checking
        TorBadge.FAILED -> R.string.tor_badge_failed
        TorBadge.OFF -> R.string.tor_badge_off
    }
    IconButton(onClick = onClick) {
        Icon(if (badge == TorBadge.OFF) OnionOffIcon else OnionIcon, stringResource(label), tint = tint)
    }
}
