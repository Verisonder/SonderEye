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
import androidx.compose.ui.unit.sp
import com.verisonder.sondereye.R

/*
 * Aeronautical chart, night edition by default: dark panels (or white chart paper, in
 * the menu), sectional-chart magenta for what matters most, chart blue for what you can
 * open. One typeface, Barlow Semi Condensed, from road-sign lettering.
 */
object Palette {
    /** Dark panels (default) or light chart paper; switched in the menu. */
    var dark by mutableStateOf(true)

    val space = Color(0xFF03060A)
    // Opaque: the globe must never show through text.
    val panel get() = if (dark) Color(0xFF151A20) else Color(0xFFFBFBF8)
    val line get() = if (dark) Color(0xFF2B323B) else Color(0xFFD9D7D0)
    val text get() = if (dark) Color(0xFFE9E7E2) else Color(0xFF15171B)
    val dim get() = if (dark) Color(0xFF9BA2AA) else Color(0xFF5E636A)
    /** Sectional-chart magenta: north, the active tool, what needs attention. */
    val magenta get() = if (dark) Color(0xFFE2489E) else Color(0xFFB0126B)
    /** Chart blue: links and actions. */
    val accent get() = if (dark) Color(0xFF79B2FF) else Color(0xFF1D5BA6)
    val error get() = if (dark) Color(0xFFFF7A70) else Color(0xFFC62828)

    // Marker colours on the globe (dark background).
    val shallow = Color(0xFFFFB547)
    val mid = Color(0xFFFF5E57)
    val deep = Color(0xFFB06CFF)
    val flight = Color(0xFFF2F5F8)
    val satellite = Color(0xFF7CF0C8)
    val me = Color(0xFF3D8BFF)
    val alpr = Color(0xFFFF4D6D)
    val camera = Color(0xFFC77DFF)
    val ship = Color(0xFF4DD0E1)
    val webcam = Color(0xFFFFE066)
    val fire = Color(0xFFFF3D00)

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

val Barlow = FontFamily(
    Font(R.font.barlow_sc_regular, FontWeight.Normal),
    Font(R.font.barlow_sc_medium, FontWeight.Medium),
    Font(R.font.barlow_sc_semibold, FontWeight.SemiBold),
    Font(R.font.barlow_sc_semibold, FontWeight.Bold),
)

/** Tabular figures, so coordinates and counts do not jiggle as they change. */
val Figures = TextStyle(fontFamily = Barlow, fontFeatureSettings = "tnum")

@Composable
fun EyeTheme(content: @Composable () -> Unit) {
    val base = TextStyle(fontFamily = Barlow)
    MaterialTheme(
        colorScheme = if (Palette.dark) darkColorScheme(
            primary = Palette.magenta,
            onPrimary = Color.White,
            primaryContainer = Palette.magenta.copy(alpha = 0.25f),
            secondaryContainer = Palette.magenta.copy(alpha = 0.25f),
            onSecondaryContainer = Palette.text,
            secondary = Palette.accent,
            background = Palette.panel,
            surface = Palette.panel,
            onSurface = Palette.text,
            onSurfaceVariant = Palette.dim,
            outline = Palette.line,
            error = Palette.error,
        ) else lightColorScheme(
            primary = Palette.magenta,
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
        content = content,
    )
}
