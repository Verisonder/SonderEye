package com.verisonder.sondereye.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object Palette {
    val space = Color(0xFF03060A)
    /** Overlays sit on imagery of any colour, so they are nearly opaque. */
    val panel = Color(0xEB0B121A)
    val line = Color(0xFF1F2B38)
    val text = Color(0xFFE6EDF3)
    val dim = Color(0xFF93A1AF)
    val accent = Color(0xFF4FC3F7) // shared with the Verisonder family
    val error = Color(0xFFFF7A70)

    // Same ramp as the markers on the globe (globe/index.html).
    val shallow = Color(0xFFFFB547)
    val mid = Color(0xFFFF5E57)
    val deep = Color(0xFFB06CFF)

    val flight = Color(0xFFF2F5F8)
    val satellite = Color(0xFF7CF0C8)
    val me = Color(0xFF3D8BFF)

    /** NASA EONET categories. */
    fun event(category: String): Color = when (category) {
        "wildfires" -> Color(0xFFFF6A3D)
        "volcanoes" -> Color(0xFFE0301E)
        "severeStorms" -> Color(0xFF8C9BFF)
        "seaLakeIce" -> Color(0xFF9FE3FF)
        "floods" -> Color(0xFF3FA7FF)
        else -> Color(0xFFFFD166)
    }

    fun depth(km: Double): Color = when {
        km < 70 -> shallow
        km < 300 -> mid
        else -> deep
    }
}

@Composable
fun EyeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.accent,
            onPrimary = Palette.space,
            background = Palette.space,
            surface = Palette.panel,
            onSurface = Palette.text,
            onSurfaceVariant = Palette.dim,
            outline = Palette.line,
            error = Palette.error,
        ),
        content = content,
    )
}
