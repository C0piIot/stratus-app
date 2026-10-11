package dev.stratus.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable

// iOS has no system-wide palette for an app to adopt: light and dark are the
// whole of what it publishes, and Compose already reads those.
@Composable
internal actual fun platformColors(dark: Boolean): ColorScheme? = null
