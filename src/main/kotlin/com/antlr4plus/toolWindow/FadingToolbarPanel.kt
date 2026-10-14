package com.antlr4plus.toolWindow

import java.awt.AlphaComposite
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.BoxLayout
import javax.swing.JPanel

/** Fades toolbar buttons as their centers approach the right edge. */
internal class FadingToolbarPanel(private val fadeZoneWidth: Int) : JPanel() {
    init {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
    }

    @Suppress("KotlinConstantConditions")
    override fun paintChildren(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        val visibleWidth = width
        val fadeStart = visibleWidth - fadeZoneWidth

        for (i in 0 until componentCount) {
            val child = getComponent(i)
            if (!child.isVisible) continue

            val childRight = child.x + child.width
            val childLeft = child.x

            val alpha =
                when {
                    childRight <= fadeStart -> 1.0f
                    childLeft < visibleWidth && childRight > fadeStart -> {
                        val centerX = childLeft + child.width / 2f
                        if (centerX < fadeStart) {
                            1.0f
                        } else if (centerX > visibleWidth) {
                            0.0f
                        } else {
                            1.0f - ((centerX - fadeStart) / fadeZoneWidth)
                        }
                    }
                    childLeft >= visibleWidth -> 0.0f
                    else -> 1.0f
                }

            if (alpha > 0.01f) {
                val childG = g2.create(child.x, child.y, child.width, child.height) as Graphics2D
                childG.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha.coerceIn(0f, 1f))
                child.paint(childG)
                childG.dispose()
            }
        }

        g2.dispose()
    }
}
