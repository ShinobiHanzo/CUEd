package dev.cued.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/**
 * The nine colours everything is drawn with. Same keys as the desktop
 * client's theme (`bg, panel, panel2, text, muted, accent, accent2, danger,
 * border`), so a theme saved on one device can be carried to the others by
 * the account's shared settings. Values are `#rrggbb` strings.
 */
data class ThemeColors(
    val bg: String = "#0e0f13",
    val panel: String = "#16181f",
    val panel2: String = "#222530",
    val text: String = "#e8eaf0",
    val muted: String = "#8a90a3",
    val accent: String = "#5ef2c6",
    val accent2: String = "#ffb86b",
    val danger: String = "#ff6b7a",
    val border: String = "#222530",
) {
    fun get(key: String): String = when (key) {
        "bg" -> bg; "panel" -> panel; "panel2" -> panel2; "text" -> text; "muted" -> muted
        "accent" -> accent; "accent2" -> accent2; "danger" -> danger; "border" -> border; else -> ""
    }

    fun with(key: String, value: String): ThemeColors = when (key) {
        "bg" -> copy(bg = value); "panel" -> copy(panel = value); "panel2" -> copy(panel2 = value); "text" -> copy(text = value)
        "muted" -> copy(muted = value); "accent" -> copy(accent = value); "accent2" -> copy(accent2 = value)
        "danger" -> copy(danger = value); "border" -> copy(border = value); else -> this
    }

    companion object {
        val KEYS = listOf("bg", "panel", "panel2", "text", "muted", "accent", "accent2", "danger", "border")
        val LABELS = mapOf(
            "bg" to "Background", "panel" to "Panel", "panel2" to "Panel (raised)", "text" to "Text", "muted" to "Muted text",
            "accent" to "Accent", "accent2" to "Accent 2", "danger" to "Danger", "border" to "Border",
        )
        val DEFAULT = ThemeColors()

        /** The desktop's presets, plus the phone's own default. */
        val PRESETS: List<Pair<String, ThemeColors>> = listOf(
            "Default dark" to DEFAULT,
            "Light" to ThemeColors(bg = "#f6f7fb", panel = "#ffffff", panel2 = "#eceef5", text = "#14161c", muted = "#5d6372", accent = "#0f9f7e", accent2 = "#3b63d6", danger = "#c62839", border = "#d7dae3"),
            "High contrast" to ThemeColors(bg = "#000000", panel = "#0a0a0a", panel2 = "#161616", text = "#ffffff", muted = "#c8c8c8", accent = "#ffd400", accent2 = "#00e5ff", danger = "#ff3b3b", border = "#444444"),
            "Warm" to ThemeColors(bg = "#17120f", panel = "#201915", panel2 = "#2b221c", text = "#f2e9df", muted = "#a4957f", accent = "#ff9f43", accent2 = "#f0c987", danger = "#ff6b6b", border = "#3a2e26"),
        )

        /** `#rgb`, `#rrggbb` or `#rrggbbaa` (case-insensitive) → the same colour as `#rrggbb` lower-case; null when it is not a colour. */
        fun normalise(hex: String): String? {
            val t = hex.trim().removePrefix("#").lowercase()
            if (t.any { Character.digit(it, 16) < 0 }) return null
            return when (t.length) {
                3 -> "#" + t.map { "$it$it" }.joinToString("")
                6 -> "#$t"
                8 -> "#" + t.substring(0, 6)
                else -> null
            }
        }
    }
}

/** Parses `#rrggbb`; a bad string falls back to [fallback] rather than crashing a draw. */
fun colorOf(hex: String, fallback: Color = Color.Magenta): Color {
    val n = ThemeColors.normalise(hex) ?: return fallback
    return Color(0xFF000000L or n.substring(1).toLong(16))
}

/**
 * The live palette. Every colour below is a getter on this, so a theme change
 * recolours the whole app at once (Compose readers see the state write).
 * Developer mode gates who may change it; [dev.cued.app.Graph] feeds it.
 */
object CuedPalette {
    var colors: ThemeColors by mutableStateOf(ThemeColors.DEFAULT)
}

val Teal: Color get() = colorOf(CuedPalette.colors.accent, Color(0xFF5EF2C6))
val Amber: Color get() = colorOf(CuedPalette.colors.accent2, Color(0xFFFFB86B))
val Ink: Color get() = colorOf(CuedPalette.colors.bg, Color(0xFF0E0F13))
val Ink2: Color get() = colorOf(CuedPalette.colors.panel, Color(0xFF16181F))
val Ink3: Color get() = colorOf(CuedPalette.colors.panel2, Color(0xFF222530))
val Mist: Color get() = colorOf(CuedPalette.colors.text, Color(0xFFE8EAF0))
val Muted: Color get() = colorOf(CuedPalette.colors.muted, Color(0xFF8A90A3))
val Danger: Color get() = colorOf(CuedPalette.colors.danger, Color(0xFFFF6B7A))
val Border: Color get() = colorOf(CuedPalette.colors.border, Color(0xFF222530))

/** Dark by default: this is a music player, it lives in pockets and dim rooms. The palette can repaint it. */
@Composable
fun CuedTheme(content: @Composable () -> Unit) {
    val colors = CuedPalette.colors
    val scheme = remember(colors) {
        darkColorScheme(
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
            outline = Border,
            error = Danger,
        )
    }
    MaterialTheme(colorScheme = scheme, content = content)
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
