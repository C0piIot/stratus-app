package dev.stratus.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image

// Skia is what Compose draws with here, so it is already in the binary.
internal actual fun decodePicture(bytes: ByteArray): ImageBitmap? =
    try {
        Image.makeFromEncoded(bytes).toComposeImageBitmap()
    } catch (_: Exception) {
        null
    }
