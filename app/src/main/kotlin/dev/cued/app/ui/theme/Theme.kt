package dev.cued.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Teal = Color(0xFF5EF2C6)
val Amber = Color(0xFFFFB86B)
val Ink = Color(0xFF0E0F13)
val Ink2 = Color(0xFF16181F)
val Ink3 = Color(0xFF222530)
val Mist = Color(0xFFE8EAF0)
val Muted = Color(0xFF8A90A3)

private val Scheme = darkColorScheme(
    primary = Teal,
    onPrimary = Ink,
    secondary = Amber,
    onSecondary = Ink,
    background = Ink,
    onBackground = Mist,
    surface = Ink2,
    onSurface = Mist,
    surfaceVariant = Ink3,
    onSurfaceVariant = Muted,
    outline = Ink3,
    error = Color(0xFFFF6B7A),
)

/** Dark only: this is a music player, it lives in pockets and dim rooms. */
@Composable
fun CuedTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, content = content)
}

/** Colour ramp for spectrogram intensity 0..1: ink -> teal -> amber -> white. */
fun spectrumColor(v: Float): Color {
    val t = v.coerceIn(0f, 1f)
    return when {
        t < 0.35f -> lerp(Ink2, Teal.copy(alpha = 1f), t / 0.35f)
        t < 0.75f -> lerp(Teal, Amber, (t - 0.35f) / 0.40f)
        else -> lerp(Amber, Color.White, (t - 0.75f) / 0.25f)
    }
}

private fun lerp(a: Color, b: Color, t: Float): Color = Color(
    red = a.red + (b.red - a.red) * t,
    green = a.green + (b.green - a.green) * t,
    blue = a.blue + (b.blue - a.blue) * t,
    alpha = 1f,
)
