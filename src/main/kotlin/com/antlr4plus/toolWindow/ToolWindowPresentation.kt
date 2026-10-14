package com.antlr4plus.toolWindow

import com.antlr4plus.state.GrammarType
import com.antlr4plus.state.PreviewState
import com.antlr4plus.util.escapeHtml
import com.intellij.ide.plugins.cl.PluginAwareClassLoader
import com.intellij.openapi.wm.ToolWindow
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Component
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Insets
import java.awt.RenderingHints
import javax.swing.Icon
import javax.swing.JLabel
import javax.swing.Timer

internal object ToolWindowTitle {
    private const val BULLET = "•"
    private const val BASE_TITLE = "ANTLR4+"

    private const val PANE_COLOR_DARK = "#B0B0B0"
    private const val PANE_COLOR_LIGHT = "#000000"
    private const val RULE_NAME_COLOR_DARK = "#E9B859"
    private const val RULE_NAME_COLOR_LIGHT = "#000000"

    private val versionedBaseTitle: String by lazy {
        val version = (ToolWindowTitle::class.java.classLoader as? PluginAwareClassLoader)?.pluginDescriptor?.version
        if (version.isNullOrBlank()) BASE_TITLE else "$BASE_TITLE $version"
    }

    private fun isLightTheme(): Boolean {
        val bg = UIUtil.getPanelBackground()
        return (bg.red + bg.green + bg.blue) / 3 > 128
    }

    private fun getPaneColor(): String = if (isLightTheme()) PANE_COLOR_LIGHT else PANE_COLOR_DARK

    private fun getRuleNameColor(): String = if (isLightTheme()) RULE_NAME_COLOR_LIGHT else RULE_NAME_COLOR_DARK

    fun build(paneName: String, state: PreviewState): String {
        val parts = mutableListOf<String>()

        parts.add("<span style='color:${getPaneColor()};'>${escapeHtml(paneName)}</span>")

        if (state.grammarType != GrammarType.NONE) {
            parts.add(state.grammarType.displayName)
        }

        if (state.startRuleName != null) {
            parts.add(
                "<span style='color:${getRuleNameColor()};'>${escapeHtml(requireNotNull(state.startRuleName))}</span>",
            )
        }

        return "<html><nobr>${parts.joinToString(" $BULLET ")}</nobr></html>"
    }

    fun baseTitle(): String = versionedBaseTitle
}

/** Keeps the sidebar title fixed while the window header reflects the active pane. */
internal class ToolWindowTitleManager(private val toolWindow: ToolWindow) {
    init {
        toolWindow.stripeTitle = ToolWindowTitle.baseTitle()
    }

    fun setTitle(title: String) {
        toolWindow.title = title
    }
}

internal class FadingToast : JLabel() {
    private var fadeTimer: Timer? = null
    private var currentAlpha = 0f

    init {
        isOpaque = false
        horizontalAlignment = CENTER
        font = font.deriveFont(Font.PLAIN, 11f)
        foreground = JBColor(Color(100, 100, 100), Color(180, 180, 180))
        isVisible = false
    }

    fun showMessage(message: String, durationMs: Int = 2000) {
        text = message
        currentAlpha = 1f
        isVisible = true
        repaint()

        fadeTimer?.stop()
        fadeTimer =
            Timer(50) { e ->
                currentAlpha -= 0.05f
                if (currentAlpha <= 0) {
                    currentAlpha = 0f
                    isVisible = false
                    (e.source as Timer).stop()
                }
                repaint()
            }
        fadeTimer?.initialDelay = durationMs
        fadeTimer?.start()
    }

    override fun paintComponent(g: Graphics) {
        if (currentAlpha > 0) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, currentAlpha)
            super.paintComponent(g2)
            g2.dispose()
        }
    }
}

/** Letter icon using the bundled italic serif font when available. */
internal class ItalicLetterIcon(
    private val letter: String = "L",
    private val size: Int = 16,
) : Icon {
    companion object {
        private val embeddedFont: Font? by lazy {
            try {
                val fontStream = ItalicLetterIcon::class.java.getResourceAsStream("/fonts/NotoSerif-BoldItalic.ttf")
                fontStream?.use { stream ->
                    Font.createFont(Font.TRUETYPE_FONT, stream)
                }
            } catch (_: Exception) {
                null
            }
        }

        private fun getIconColor(): Color = if (JBColor.isBright()) Color(0x6C, 0x70, 0x7E) else Color(0xCE, 0xD0, 0xD6)
    }

    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.setRenderingHint(
            RenderingHints.KEY_TEXT_ANTIALIASING,
            RenderingHints.VALUE_TEXT_ANTIALIAS_ON,
        )

        g2.color = getIconColor()

        val fontSize = (size * 1.1f).toInt()
        g2.font =
            embeddedFont?.deriveFont(Font.BOLD or Font.ITALIC, fontSize.toFloat())
                ?: Font("Serif", Font.ITALIC or Font.BOLD, fontSize)

        val metrics = g2.fontMetrics
        val textWidth = metrics.stringWidth(letter)

        // Compensate for the italic slant when centering.
        val textX = x + (size - textWidth) / 2 - 1
        val textY = y + (size + metrics.ascent - metrics.descent) / 2

        g2.drawString(letter, textX, textY)
        g2.dispose()
    }

    override fun getIconWidth(): Int = size

    override fun getIconHeight(): Int = size
}

internal class ConsoleLetterIcon(
    private val letter: String,
    private val size: Int = 16,
) : Icon {
    companion object {
        private val embeddedFont: Font? by lazy {
            try {
                val fontStream = ConsoleLetterIcon::class.java.getResourceAsStream("/fonts/NotoSerif-BoldItalic.ttf")
                fontStream?.use { stream ->
                    Font.createFont(Font.TRUETYPE_FONT, stream)
                }
            } catch (_: Exception) {
                null
            }
        }

        private fun getIconColor(): Color = if (JBColor.isBright()) Color(0x6C, 0x70, 0x7E) else Color(0xCE, 0xD0, 0xD6)
    }

    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.setRenderingHint(
            RenderingHints.KEY_TEXT_ANTIALIASING,
            RenderingHints.VALUE_TEXT_ANTIALIAS_ON,
        )

        g2.color = getIconColor()

        val rectY = y + 1
        val rectWidth = size - 1
        val rectHeight = size - 2
        val arcSize = 3
        g2.stroke = BasicStroke(1.2f)
        g2.drawRoundRect(x, rectY, rectWidth, rectHeight, arcSize, arcSize)

        val fontSize = (size * 0.7f).toInt()
        g2.font =
            embeddedFont?.deriveFont(Font.BOLD or Font.ITALIC, fontSize.toFloat())
                ?: Font("Serif", Font.ITALIC or Font.BOLD, fontSize)

        val metrics = g2.fontMetrics
        val textWidth = metrics.stringWidth(letter)

        val textX = x + (size - textWidth) / 2
        val textY = y + (size + metrics.ascent - metrics.descent) / 2

        g2.drawString(letter, textX, textY)
        g2.dispose()
    }

    override fun getIconWidth(): Int = size

    override fun getIconHeight(): Int = size
}

internal object ToolWindowTheme {
    const val BUTTON_SIZE = 32
    const val BADGE_SIZE = 14
    const val BADGE_FONT_SIZE = 9
    const val BORDER_RADIUS = 8
    const val BORDER_STROKE = 1f
    const val DISABLED_OVERLAY_ALPHA = 0.8f

    private val SELECTION_COLOR_LIGHT = Color(0xA6, 0xD2, 0xFF)
    private val SELECTION_COLOR_DARK =
        Color(
            (0xA6 * 0.4).toInt(),
            (0xD2 * 0.4).toInt(),
            (0xFF * 0.4).toInt(),
        )

    fun getSelectionColor(): Color = if (JBColor.isBright()) SELECTION_COLOR_LIGHT else SELECTION_COLOR_DARK

    fun drawPaneButtonBackground(
        g: Graphics,
        width: Int,
        height: Int,
        isActive: Boolean,
        isHovered: Boolean,
    ) {
        if (isActive || isHovered) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            if (!isActive) {
                g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.5f)
                g2.color = JBUI.CurrentTheme.ActionButton.hoverBackground()
            } else {
                g2.color = getSelectionColor()
            }
            g2.fillRoundRect(1, 1, width - 3, height - 3, BORDER_RADIUS, BORDER_RADIUS)
            g2.dispose()
        }
    }

    fun createRoundedBorder(padding: Int = 5, margin: Int = 0): javax.swing.border.Border =
        object : javax.swing.border.AbstractBorder() {
            override fun paintBorder(
                c: Component,
                g: Graphics,
                x: Int,
                y: Int,
                width: Int,
                height: Int,
            ) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(
                    RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON,
                )
                g2.color = JBColor.border()
                g2.stroke = BasicStroke(BORDER_STROKE)
                g2.drawRoundRect(
                    x + margin + 1,
                    y + 1,
                    width - (margin * 2) - 3,
                    height - 3,
                    BORDER_RADIUS,
                    BORDER_RADIUS,
                )
                g2.dispose()
            }

            override fun getBorderInsets(c: Component): Insets = Insets(2, margin + padding, 2, margin + padding)

            override fun getBorderInsets(c: Component, insets: Insets): Insets {
                insets.set(2, margin + padding, 2, margin + padding)
                return insets
            }
        }
}
