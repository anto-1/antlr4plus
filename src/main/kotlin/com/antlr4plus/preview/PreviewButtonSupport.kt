package com.antlr4plus.preview

import com.antlr4plus.color.buttonColor
import com.antlr4plus.icons.Icons
import com.intellij.util.IconUtil
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.Ellipse2D
import javax.swing.JButton

internal class PreviewButtonInteraction(private val button: JButton) {
    var isHovered = false
        private set

    var isPressed = false
        private set

    fun install(width: Int, height: Int, onClick: () -> Unit) {
        button.preferredSize = Dimension(width, height)
        button.minimumSize = Dimension(width, height)
        button.maximumSize = Dimension(width, height)
        button.isOpaque = false
        button.isContentAreaFilled = false
        button.isBorderPainted = false
        button.isFocusPainted = false
        button.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

        button.addMouseListener(
            object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) {
                    isHovered = true
                    button.repaint()
                }

                override fun mouseExited(e: MouseEvent) {
                    isHovered = false
                    isPressed = false
                    button.repaint()
                }

                override fun mousePressed(e: MouseEvent) {
                    isPressed = true
                    button.repaint()
                }

                override fun mouseReleased(e: MouseEvent) {
                    isPressed = false
                    button.repaint()
                    if (isHovered) {
                        onClick()
                    }
                }
            },
        )
    }
}

internal fun JButton.containsCircularPreviewPoint(x: Int, y: Int): Boolean {
    val centerX = (width - 4) / 2
    val centerY = (height - 4) / 2
    val radius = (width - 4) / 2
    val dx = x - centerX
    val dy = y - centerY
    return dx * dx + dy * dy <= radius * radius
}

internal fun JButton.paintCopyTreeButton(
    g: Graphics,
    interaction: PreviewButtonInteraction,
) {
    val g2 = g.create() as Graphics2D
    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

    g2.color = Color(0, 0, 0, 40)
    g2.fill(Ellipse2D.Double(2.0, 3.0, (width - 4).toDouble(), (height - 4).toDouble()))

    val baseBrightness = 0.02
    val stateBrightness =
        when {
            interaction.isPressed -> 0.08
            interaction.isHovered -> 0.04
            else -> 0.0
        }
    g2.color = buttonColor(baseBrightness + stateBrightness)
    g2.fill(Ellipse2D.Double(0.0, 0.0, (width - 4).toDouble(), (height - 4).toDouble()))

    val iconSize = 22
    val scaledIcon = IconUtil.scale(Icons.COPY, null, iconSize.toFloat() / Icons.COPY.iconWidth)
    val iconX = (width - 4 - iconSize) / 2
    val iconY = (height - 4 - iconSize) / 2

    val originalComposite = g2.composite
    if (!interaction.isHovered) {
        g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.8f)
    }
    scaledIcon.paintIcon(this, g2, iconX, iconY)
    g2.composite = originalComposite

    g2.dispose()
}
