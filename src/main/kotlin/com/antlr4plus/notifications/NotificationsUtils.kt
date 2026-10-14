package com.antlr4plus.notifications

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.wm.WindowManager
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.geom.RoundRectangle2D
import javax.swing.*

object NotificationsUtils {
    private const val CORNER_RADIUS = 10
    private const val BORDER_WIDTH = 1.0f
    private const val FONT_SIZE = 13f

    // Resolve colors when used so theme changes are reflected.
    private fun toastBgColor(): Color = if (JBColor.isBright()) Color(0xE8, 0xEE, 0xF5) else Color(0x2E, 0x43, 0x6E)

    private fun toastTextColor(): Color = if (JBColor.isBright()) Color(0x1E, 0x1E, 0x1E) else Color(0xDF, 0xE1, 0xE5)

    private fun toastBorderColor(): Color = if (JBColor.isBright()) Color(0x35, 0x74, 0xF0) else Color(0x5A, 0x8F, 0xD4)

    private const val DISPLAY_DURATION_MS = 4000
    private const val FADE_DURATION_MS = 500
    private const val FADE_STEPS = 20

    /** Shows a temporary toast near the bottom of the project window. */
    fun showToast(
        project: Project,
        message: String,
    ) {
        ApplicationManager.getApplication().invokeLater {
            val frame = WindowManager.getInstance().getFrame(project) ?: return@invokeLater

            val toastPanel = RoundedToastPanel(message)

            val toastWindow =
                JWindow(frame).apply {
                    isAlwaysOnTop = true
                    background = Color(0, 0, 0, 0)
                    contentPane = toastPanel
                    pack()
                }

            val frameSize = frame.size
            val toastSize = toastWindow.size
            val x = frame.x + (frameSize.width - toastSize.width) / 2
            val y = frame.y + frameSize.height - (frameSize.height / 12) - toastSize.height

            toastWindow.setLocation(x, y)
            toastWindow.opacity = 1.0f
            toastWindow.isVisible = true

            Timer(DISPLAY_DURATION_MS) {
                    fadeOutAndDispose(toastWindow)
                }
                .apply {
                    isRepeats = false
                    start()
                }
        }
    }

    private fun fadeOutAndDispose(window: JWindow) {
        val stepDelay = FADE_DURATION_MS / FADE_STEPS
        var currentStep = 0

        Timer(stepDelay) { event ->
                currentStep++
                val progress = currentStep.toFloat() / FADE_STEPS
                // Cubic ease-out.
                val easedProgress = 1 - (1 - progress) * (1 - progress) * (1 - progress)
                val newOpacity = 1.0f - easedProgress

                if (currentStep >= FADE_STEPS) {
                    (event.source as Timer).stop()
                    window.isVisible = false
                    window.dispose()
                } else {
                    window.opacity = newOpacity.coerceIn(0f, 1f)
                }
            }
            .start()
    }

    private class RoundedToastPanel(
        message: String,
    ) : JPanel() {

        init {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            border = JBUI.Borders.empty(6, 10)

            val iconLabel =
                JLabel().apply {
                    val baseIcon =
                        IconLoader.getIcon(
                            "/icons/com/antlr4plus/toolWindow_monochrome_13_13.svg",
                            NotificationsUtils::class.java,
                        )
                    icon = com.intellij.util.IconUtil.scale(baseIcon, null, 1.25f)
                    border = JBUI.Borders.emptyRight(8)
                    isOpaque = false
                }

            val textLabel =
                JLabel(message).apply {
                    foreground = toastTextColor()
                    font = font.deriveFont(FONT_SIZE)
                    isOpaque = false
                }

            add(iconLabel)
            add(textLabel)
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

            val roundRect =
                RoundRectangle2D.Float(
                    BORDER_WIDTH / 2,
                    BORDER_WIDTH / 2,
                    width.toFloat() - BORDER_WIDTH,
                    height.toFloat() - BORDER_WIDTH,
                    CORNER_RADIUS.toFloat() * 2,
                    CORNER_RADIUS.toFloat() * 2,
                )

            g2.color = toastBgColor()
            g2.fill(roundRect)

            g2.color = toastBorderColor()
            g2.stroke = BasicStroke(BORDER_WIDTH)
            g2.draw(roundRect)

            g2.dispose()
            super.paintComponent(g)
        }
    }
}
