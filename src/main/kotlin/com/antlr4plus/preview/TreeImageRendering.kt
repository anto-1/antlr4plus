package com.antlr4plus.preview

import com.antlr4plus.preview.tree.TreeViewer
import com.intellij.ui.JBColor
import java.awt.Dimension
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.image.BufferedImage

/** Image dimensions before applying the export scale factor. */
internal fun TreeViewer.getExportBaseSize(viewportBounds: Rectangle?): Dimension? {
    if (viewportBounds != null) {
        return Dimension(viewportBounds.width, viewportBounds.height)
    }

    // Full-tree export uses layout bounds because the component may be constrained by its viewport.
    val treeBounds = treeLayout?.bounds
    if (treeBounds == null || treeBounds.width <= 0 || treeBounds.height <= 0) return null

    val scale = this.scale
    val padding = viewportPadding
    return Dimension(
        ((treeBounds.width + padding * 2) * scale).toInt(),
        ((treeBounds.height + padding * 2) * scale).toInt(),
    )
}

internal fun renderTreeImage(
    parseTreeViewer: TreeViewer,
    width: Int,
    height: Int,
    useTransparentBackground: Boolean,
    viewportBounds: Rectangle?,
    scaleFactor: Double,
): BufferedImage {
    val imageType = if (useTransparentBackground) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB
    val bi = BufferedImage(width, height, imageType)
    val g2 = bi.createGraphics()

    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g2.setRenderingHint(
        RenderingHints.KEY_TEXT_ANTIALIASING,
        RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB,
    )
    g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
    g2.setRenderingHint(
        RenderingHints.KEY_FRACTIONALMETRICS,
        RenderingHints.VALUE_FRACTIONALMETRICS_ON,
    )
    g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
    g2.setRenderingHint(
        RenderingHints.KEY_INTERPOLATION,
        RenderingHints.VALUE_INTERPOLATION_BICUBIC,
    )

    // An explicit clip prevents painting from falling back to the smaller component bounds.
    g2.setClip(0, 0, width, height)

    if (!useTransparentBackground) {
        g2.color = JBColor.WHITE
        g2.fillRect(0, 0, width, height)
    }

    if (scaleFactor != 1.0) {
        g2.scale(scaleFactor, scaleFactor)
    }

    if (viewportBounds != null) {
        g2.translate(-viewportBounds.x, -viewportBounds.y)
    }

    parseTreeViewer.paintForExport(g2, fillBackground = false)
    g2.dispose()

    return bi
}
