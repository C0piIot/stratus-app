package dev.stratus.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * The phone's palette where it has one, and ours where it does not.
 *
 * An app that stays white while the phone is dark is the first thing anybody
 * notices, and it was the state this shipped in: `MaterialTheme {}` with no
 * arguments is Material's own light scheme whatever the system is set to.
 */
@Composable
fun StratusTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(colorScheme = platformColors(dark) ?: stratus(dark), content = content)
}

/**
 * What the platform itself says the colours are, or null where it says nothing.
 *
 * Android 12 and up derives a scheme from the wallpaper, which is what makes an
 * app look like it belongs on that phone rather than like a port. iOS has no
 * equivalent -- the system's own apps pick their own colours -- so it answers
 * null and gets [stratus].
 */
@Composable
internal expect fun platformColors(dark: Boolean): ColorScheme?

/** Ours: one blue, enough roles for the surfaces this app has, both ways round. */
private fun stratus(dark: Boolean): ColorScheme = if (dark) {
    darkColorScheme(
        primary = Color(0xFFA0CAFD),
        onPrimary = Color(0xFF003258),
        primaryContainer = Color(0xFF194975),
        onPrimaryContainer = Color(0xFFD1E4FF),
        secondary = Color(0xFFBBC7DB),
        onSecondary = Color(0xFF253140),
        background = Color(0xFF111418),
        onBackground = Color(0xFFE1E2E8),
        surface = Color(0xFF111418),
        onSurface = Color(0xFFE1E2E8),
        surfaceVariant = Color(0xFF43474E),
        onSurfaceVariant = Color(0xFFC3C6CF),
        outline = Color(0xFF8D9199),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6),
    )
} else {
    lightColorScheme(
        primary = Color(0xFF36618E),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFD1E4FF),
        onPrimaryContainer = Color(0xFF001D36),
        secondary = Color(0xFF535F70),
        onSecondary = Color(0xFFFFFFFF),
        background = Color(0xFFF8F9FF),
        onBackground = Color(0xFF191C20),
        surface = Color(0xFFF8F9FF),
        onSurface = Color(0xFF191C20),
        surfaceVariant = Color(0xFFDFE2EB),
        onSurfaceVariant = Color(0xFF43474E),
        outline = Color(0xFF73777F),
        error = Color(0xFFBA1A1A),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF410002),
    )
}
