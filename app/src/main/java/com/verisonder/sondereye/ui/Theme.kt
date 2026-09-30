package com.verisonder.sondereye.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.graphics.Shadow
import com.verisonder.sondereye.R

/*
 * Wasteland terminal: everything in one phosphor green on near-black, as on a Pip-Boy.
 * Bright green for what matters and what is picked (picked rows are solid green with dark
 * text), a darker green for what is secondary or off, thin green frames with square
 * corners, and a faint glow on the text. Problems stay red. One typeface, Roboto Condensed.
 */
object Palette {
    /** Dark scope panels (default) or light ones; switched in the menu. */
    var dark by mutableStateOf(true)

    /** Colours picked in the menu for dot layers, by layer name (ARGB); none: their own. */
    var dotColors by mutableStateOf<Map<String, Int>>(emptyMap())
    private fun custom(layer: String): Color? = dotColors[layer]?.let { Color(it) }

    val space = Color(0xFF03060A)
    // Opaque: the globe must never show through text.
    val panel get() = if (dark) Color(0xFF07120B) else Color(0xFFF4FAF6)
    /** Frames and dividers: a green you can see, not a hairline you can't. */
    val line get() = if (dark) Color(0xFF1B9E55) else Color(0xFFC9DDD0)
    val text get() = if (dark) Color(0xFF2EF08A) else Color(0xFF0E1F15)
    /** Secondary and unavailable: the darker green of a greyed-out Pip-Boy row. */
    val dim get() = if (dark) Color(0xFF14A353) else Color(0xFF4F6B5A)
    /** Full phosphor: picked rows, active keys, north, the sweep. */
    val signal get() = if (dark) Color(0xFF1AFF80) else Color(0xFF0B7A3E)
    /** Text on a solid signal background. */
    val onSignal get() = if (dark) Color(0xFF03130A) else Color.White
    /** The glow around text; none on light panels. */
    val glow get() = if (dark) Color(0x881AFF80) else Color.Transparent
    /** Phosphor green drawn straight over the globe (scope, readout, scale bar): always bright. */
    val scope = Color(0xFF7DF5A5)
    /** Links and actions. */
    val accent get() = if (dark) Color(0xFF1AFF80) else Color(0xFF1D5BA6)
    val error get() = if (dark) Color(0xFFFF6B57) else Color(0xFFC62828)

    // Marker colours on the globe (dark background).
    val shallow = Color(0xFFFFB547)
    val mid = Color(0xFFFF5E57)
    val deep = Color(0xFFB06CFF)
    val flight = Color(0xFFF2F5F8)
    val satellite = Color(0xFF7CF0C8)
    val me = Color(0xFF3D8BFF)
    val alpr get() = custom("cameras") ?: Color(0xFFFF4D6D)
    val camera get() = custom("cameras") ?: Color(0xFFC77DFF)
    val ship = Color(0xFF4DD0E1)
    val webcam = Color(0xFFFFE066)
    val fire get() = custom("fires") ?: Color(0xFFFF3D00)
    /** Buses, and bus lines that have no colour of their own in OpenStreetMap. */
    val bus = Color(0xFFFF9F43)
    val busStop = Color(0xFFF2F5F8)
    /** Places in the news for fighting. */
    val conflict get() = custom("conflicts") ?: Color(0xFFFF1744)

    /** NASA EONET categories. */
    fun event(category: String): Color = custom("events") ?: when (category) {
        "wildfires" -> Color(0xFFFF6A3D)
        "volcanoes" -> Color(0xFFE0301E)
        "severeStorms" -> Color(0xFF8C9BFF)
        "seaLakeIce" -> Color(0xFF9FE3FF)
        "floods" -> Color(0xFF3FA7FF)
        else -> Color(0xFFFFD166)
    }

    fun depth(km: Double): Color = custom("quakes") ?: when {
        km < 70 -> shallow
        km < 300 -> mid
        else -> deep
    }

    /** A dot layer's own colours, whatever is picked (the menu's Default swatch). */
    fun ownDots(layer: String): List<Color> = when (layer) {
        "fires" -> listOf(Color(0xFFFF3D00))
        "conflicts" -> listOf(Color(0xFFFF1744))
        "cameras" -> listOf(Color(0xFFC77DFF), Color(0xFFFF4D6D))
        "quakes" -> listOf(shallow, mid, deep)
        else -> listOf(Color(0xFFFF6A3D), Color(0xFF8C9BFF), Color(0xFF3FA7FF))
    }
}

val Hud = FontFamily(
    Font(R.font.roboto_condensed_regular, FontWeight.Normal),
    Font(R.font.roboto_condensed_medium, FontWeight.Medium),
    Font(R.font.roboto_condensed_bold, FontWeight.SemiBold),
    Font(R.font.roboto_condensed_bold, FontWeight.Bold),
)

/** The phosphor glow every piece of text carries. */
val Glow get() = Shadow(Palette.glow, blurRadius = 7f)

/** Tabular figures, so coordinates and counts do not jiggle as they change. */
val Figures get() = TextStyle(fontFamily = Hud, fontFeatureSettings = "tnum", shadow = Glow)

@Composable
fun EyeTheme(content: @Composable () -> Unit) {
    val base = TextStyle(fontFamily = Hud, shadow = Glow)
    val square = RoundedCornerShape(2.dp)
    MaterialTheme(
        colorScheme = if (Palette.dark) darkColorScheme(
            primary = Palette.signal,
            onPrimary = Palette.onSignal,
            primaryContainer = Palette.signal.copy(alpha = 0.25f),
            secondaryContainer = Palette.signal.copy(alpha = 0.25f),
            onSecondaryContainer = Palette.text,
            secondary = Palette.accent,
            background = Palette.panel,
            surface = Palette.panel,
            onSurface = Palette.text,
            onSurfaceVariant = Palette.dim,
            outline = Palette.line,
            error = Palette.error,
        ) else lightColorScheme(
            primary = Palette.signal,
            onPrimary = Color.White,
            secondary = Palette.accent,
            background = Palette.panel,
            surface = Palette.panel,
            onSurface = Palette.text,
            onSurfaceVariant = Palette.dim,
            outline = Palette.line,
            error = Palette.error,
        ),
        typography = Typography(
            bodyLarge = base.copy(fontSize = 16.sp, lineHeight = 22.sp),
            bodyMedium = base.copy(fontSize = 14.sp, lineHeight = 19.sp),
            bodySmall = base.copy(fontSize = 12.sp, lineHeight = 16.sp),
            titleLarge = base.copy(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
            titleMedium = base.copy(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
            labelLarge = base.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium),
            labelMedium = base.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium),
        ),
        // Square corners everywhere, as on a terminal.
        shapes = Shapes(extraSmall = square, small = square, medium = square, large = square, extraLarge = square),
        content = content,
    )
}
