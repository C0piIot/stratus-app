package dev.stratus.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The Stratus mark: three bars, on the 64-unit grid it is drawn on everywhere.
 *
 * Written out rather than loaded, because the source of record -- `brand/mark.svg`
 * in the workspace repo -- has three shapes in it and a picture that small is
 * less code than the machinery to carry a file. Android already has it twice as
 * a drawable, for the launcher and for the Files sidebar, and neither of those
 * exists in a multiplatform module; this is the one both platforms can draw.
 *
 * It carries no colour of its own. `Icon` tints it, so the bar it sits in
 * decides whether it is light or dark -- the same bargain `ic_stratus_root.xml`
 * makes with `?attr/colorControlNormal` and the web makes with `currentColor`.
 */
val StratusMark: ImageVector by lazy {
    ImageVector.Builder(
        name = "StratusMark",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 64f,
        viewportHeight = 64f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            bar(x = 26f, y = 14f, width = 28f)
            bar(x = 10f, y = 27f, width = 44f)
            bar(x = 10f, y = 40f, width = 28f)
        }
    }.build()
}

/** One bar: nine units tall and fully rounded, which is `rx="4.5"` on the rect. */
private fun PathBuilder.bar(x: Float, y: Float, width: Float) {
    val radius = 4.5f
    moveTo(x + radius, y)
    horizontalLineTo(x + width - radius)
    arcTo(radius, radius, 0f, isMoreThanHalf = false, isPositiveArc = true, x + width - radius, y + 9f)
    horizontalLineTo(x + radius)
    arcTo(radius, radius, 0f, isMoreThanHalf = false, isPositiveArc = true, x + radius, y)
    close()
}
