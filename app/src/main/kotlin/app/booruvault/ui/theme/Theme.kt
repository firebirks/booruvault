package app.booruvault.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Light = lightColorScheme(
    primary = Color(0xFF5B4DB7),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE5DEFF),
    onPrimaryContainer = Color(0xFF170065),
    secondary = Color(0xFF5F5C71),
    secondaryContainer = Color(0xFFE5DFF9),
    tertiary = Color(0xFF7B5266),
    background = Color(0xFFFCF8FF),
    surface = Color(0xFFFCF8FF),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFC8BFFF),
    onPrimary = Color(0xFF2C1A87),
    primaryContainer = Color(0xFF43349E),
    onPrimaryContainer = Color(0xFFE5DEFF),
    secondary = Color(0xFFC9C3DC),
    secondaryContainer = Color(0xFF474459),
    tertiary = Color(0xFFECB8CF),
    background = Color(0xFF131218),
    surface = Color(0xFF131218),
)

@Composable
fun BooruVaultTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
