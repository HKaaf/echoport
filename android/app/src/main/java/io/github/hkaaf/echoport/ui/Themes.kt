package io.github.hkaaf.echoport.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Fixed brand colors (logo and icon only). Same as the desktop app. */
object Brand {
    val blue = Color(0xFF1B2A6B)
    val purple = Color(0xFF4A2380)
}

data class EpTheme(
    val id: String,
    val name: String,
    val dark: Boolean,
    val bg: Color,
    val surface: Color,
    val surface2: Color,
    val text: Color,
    val muted: Color,
    val accent: Color,
    val accent2: Color,
    val onAccent: Color,
    val border: Color,
)

private fun t(id: String, name: String, dark: Boolean, vararg c: Long) = EpTheme(
    id, name, dark, Color(c[0]), Color(c[1]), Color(c[2]), Color(c[3]), Color(c[4]), Color(c[5]), Color(c[6]), Color(c[7]), Color(c[8]),
)

// bg, surface, surface2, text, muted, accent, accent2, onAccent, border — mirrors desktop/src/lib/themes.ts
val THEMES = listOf(
    t("daybreak", "Daybreak", false, 0xFFF4F6FB, 0xFFFFFFFF, 0xFFEBEEF7, 0xFF141A33, 0xFF545C78, 0xFF3D3AB8, 0xFF6A3FB5, 0xFFFFFFFF, 0xFFDDE2EF),
    t("lavender", "Lavender", false, 0xFFF6F3FC, 0xFFFFFFFF, 0xFFEEE8F9, 0xFF1E1433, 0xFF5E5277, 0xFF5B2E9E, 0xFF3D3AB8, 0xFFFFFFFF, 0xFFE2DAF2),
    t("paper", "Paper", false, 0xFFFAFAF7, 0xFFFFFFFF, 0xFFF0F0EB, 0xFF1A1A1A, 0xFF5A5A55, 0xFF1F3A8A, 0xFF4B3A8A, 0xFFFFFFFF, 0xFFE3E3DC),
    t("mint", "Mint", false, 0xFFF1F8F4, 0xFFFFFFFF, 0xFFE3F1E9, 0xFF10241A, 0xFF4A6356, 0xFF1E7A4F, 0xFF2E9C8A, 0xFFFFFFFF, 0xFFD3E6DB),
    t("sunrise", "Sunrise", false, 0xFFFDF5EF, 0xFFFFFFFF, 0xFFFAE8DC, 0xFF2A160B, 0xFF6B5245, 0xFFB4531A, 0xFFC9772A, 0xFFFFFFFF, 0xFFF0DACB),
    t("lemon", "Lemon", false, 0xFFFFFCEB, 0xFFFFFFFF, 0xFFFBF3CC, 0xFF221D05, 0xFF625A35, 0xFF7A5F00, 0xFFE0B21C, 0xFFFFFFFF, 0xFFEFE5B8),
    t("midnight", "Midnight", true, 0xFF0B1020, 0xFF121A33, 0xFF1A2445, 0xFFE8ECF8, 0xFF9AA3C0, 0xFF8B7CF6, 0xFF4F7FE8, 0xFF0B1020, 0xFF26304F),
    t("nebula", "Nebula", true, 0xFF140E24, 0xFF1D1533, 0xFF281D45, 0xFFEEE8FA, 0xFFA89BC6, 0xFFB39BFA, 0xFF6E8BF0, 0xFF140E24, 0xFF33285A),
    t("graphite", "Graphite", true, 0xFF111214, 0xFF1A1C1F, 0xFF24272B, 0xFFECEDEF, 0xFFA0A4AB, 0xFF6E9BF2, 0xFF9C88F0, 0xFF0B0C0E, 0xFF2E3136),
    t("forest", "Forest", true, 0xFF0B1712, 0xFF11211A, 0xFF183026, 0xFFE3F2EA, 0xFF93B3A3, 0xFF4CC38A, 0xFF3FA7A0, 0xFF06140D, 0xFF23402F),
    t("ember", "Ember", true, 0xFF170E09, 0xFF22150E, 0xFF2F1D13, 0xFFF6E9E1, 0xFFBFA293, 0xFFFF8A4C, 0xFFE8B04A, 0xFF1A0D05, 0xFF43291B),
    t("gold", "Gold", true, 0xFF13110A, 0xFF1C1A10, 0xFF272416, 0xFFF5F0DC, 0xFFB7AE8C, 0xFFF5C542, 0xFFE58E3A, 0xFF1A1505, 0xFF3A3520),
    t("ocean", "Ocean", true, 0xFF071A1F, 0xFF0C252C, 0xFF12323B, 0xFFE1F3F6, 0xFF8DB4BD, 0xFF3CC6D6, 0xFF5B8FF0, 0xFF04161A, 0xFF1D4651),
    t("rose", "Rose", true, 0xFF1A0D14, 0xFF25131D, 0xFF331A28, 0xFFF8E6EE, 0xFFC49BAE, 0xFFF27BA6, 0xFFB98AF2, 0xFF1F0912, 0xFF4A2639),
)

fun themeById(id: String) = THEMES.firstOrNull { it.id == id } ?: THEMES[0]

val LocalEp = staticCompositionLocalOf { THEMES[0] }

@Composable
fun EchoportTheme(theme: EpTheme, content: @Composable () -> Unit) {
    val base = if (theme.dark) darkColorScheme() else lightColorScheme()
    val scheme = base.copy(
        primary = theme.accent, onPrimary = theme.onAccent,
        secondary = theme.accent2,
        background = theme.bg, onBackground = theme.text,
        surface = theme.surface, onSurface = theme.text,
        surfaceVariant = theme.surface2, onSurfaceVariant = theme.muted,
        surfaceContainer = theme.surface, surfaceContainerHigh = theme.surface2,
        outline = theme.border, outlineVariant = theme.border,
    )
    CompositionLocalProvider(LocalEp provides theme) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
