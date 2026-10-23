package dev.stratus.ui

import androidx.compose.ui.graphics.ImageBitmap

/**
 * A PNG or a JPEG as something Compose can draw, or null for bytes the
 * platform cannot read. Each platform's own decoder rather than an image
 * library: the only pictures this draws are a renderer's icon, and a
 * library would be a dependency for forty pixels.
 */
internal expect fun decodePicture(bytes: ByteArray): ImageBitmap?
