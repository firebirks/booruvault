package io.github.firebirks.booruvault.ui.common

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.PathData
import androidx.compose.ui.graphics.vector.group
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** An onion, drawn in outline like the Material icons around it: the route goes through Tor. */
val OnionIcon: ImageVector by lazy { onion(crossed = false) }

/** The onion struck through, the stroke cut back around the bar as Material's "off" icons are: Tor is off. */
val OnionOffIcon: ImageVector by lazy { onion(crossed = true) }

private fun onion(crossed: Boolean): ImageVector = ImageVector.Builder(
    name = if (crossed) "OnionOff" else "Onion",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    val ink = SolidColor(Color.Black)
    fun ImageVector.Builder.stroke(block: PathBuilder.() -> Unit) =
        path(stroke = ink, strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathBuilder = block)

    // Struck through, the onion keeps clear of a band along the bar, so the bar reads as one.
    val clear = if (crossed) {
        PathData {
            moveTo(GAP, 0f); lineTo(24f, 0f); lineTo(24f, 24f - GAP); close()
            moveTo(0f, GAP); lineTo(0f, 24f); lineTo(24f - GAP, 24f); close()
        }
    } else {
        PathData { moveTo(0f, 0f); lineTo(24f, 0f); lineTo(24f, 24f); lineTo(0f, 24f); close() }
    }
    group(clipPathData = clear) {
        // The bulb, pointed at the top where the leaves come out.
        stroke {
            moveTo(12f, 6f)
            curveTo(15f, 8.5f, 19f, 10.5f, 19f, 15f)
            curveTo(19f, 18.9f, 15.9f, 21.5f, 12f, 21.5f)
            curveTo(8.1f, 21.5f, 5f, 18.9f, 5f, 15f)
            curveTo(5f, 10.5f, 9f, 8.5f, 12f, 6f)
            close()
        }
        // Its layers.
        stroke {
            moveTo(12f, 9.5f)
            curveTo(9.8f, 11.4f, 8.6f, 13.2f, 8.6f, 15.3f)
            curveTo(8.6f, 18f, 10f, 20.2f, 12f, 21.5f)
        }
        stroke {
            moveTo(12f, 9.5f)
            curveTo(14.2f, 11.4f, 15.4f, 13.2f, 15.4f, 15.3f)
            curveTo(15.4f, 18f, 14f, 20.2f, 12f, 21.5f)
        }
        // The leaves.
        stroke {
            moveTo(12f, 6f)
            lineTo(12f, 2.5f)
        }
        stroke {
            moveTo(12f, 5f)
            curveTo(12.6f, 3.6f, 13.8f, 2.9f, 15.5f, 2.8f)
        }
    }
    if (crossed) {
        stroke {
            moveTo(3f, 3f)
            lineTo(21f, 21f)
        }
    }
}.build()

/** Half the height of the band kept clear along the bar, in viewport units. */
private const val GAP = 2.2f
