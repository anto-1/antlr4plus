package com.antlr4plus.toolWindow

import com.intellij.ui.JBColor
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.RoundRectangle2D
import javax.swing.JPanel
import javax.swing.Timer
import kotlin.math.sin

/** Displays loading status with fade transitions and cancel or retry actions. */
class LoadingOverlay : JPanel() {
    private var currentText: String = ""
    private var textOpacity: Float = 0f
    private var fadeTimer: Timer? = null
    private var isLoading: Boolean = false

    // Animation durations are in milliseconds.
    private var textUpdateTime: Long = 0
    private var showStartTime: Long = 0
    private val fadeInDuration = 150
    private val fadeOutDelay = 800
    private val fadeOutDuration = 400
    private val minDisplayTime = 300

    private val cancelButtonShowDelay = 5000L
    private var cancelButtonBounds: Rectangle? = null
    private var isCancelButtonHovered = false
    private var onCancelCallback: (() -> Unit)? = null
    private var isCancellable: Boolean = true

    private var actionButtonText: String? = null
    private var actionButtonBounds: Rectangle? = null
    private var isActionButtonHovered = false
    private var onActionCallback: (() -> Unit)? = null

    private val excludedComponents = mutableListOf<Component>()

    private val overlayColor =
        JBColor(
            Color(220, 220, 220, 180),
            Color(30, 30, 30, 220),
        )
    private val textColor =
        JBColor(
            Color(40, 40, 40),
            Color(220, 220, 220),
        )
    // Keep the accent visible when theme-provided named colors have low contrast.
    private val accentColor = JBColor(0x4A9EFF, 0x4A9EFF)
    private val cancelButtonColor =
        JBColor(
            Color(200, 200, 200),
            Color(70, 70, 70),
        )
    private val cancelButtonHoverColor =
        JBColor(
            Color(180, 180, 180),
            Color(90, 90, 90),
        )
    private val cancelButtonTextColor =
        JBColor(
            Color(40, 40, 40),
            Color(220, 220, 220),
        )

    init {
        isOpaque = false
        layout = null
        isVisible = false

        addMouseMotionListener(
            object : MouseAdapter() {
                override fun mouseMoved(e: MouseEvent) {
                    val wasHovered = isCancelButtonHovered
                    val wasActionHovered = isActionButtonHovered
                    isCancelButtonHovered = cancelButtonBounds?.contains(e.point) == true
                    isActionButtonHovered = actionButtonBounds?.contains(e.point) == true
                    if (wasHovered != isCancelButtonHovered || wasActionHovered != isActionButtonHovered) {
                        cursor =
                            if (isCancelButtonHovered || isActionButtonHovered)
                                Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                            else Cursor.getDefaultCursor()
                        repaint()
                    }
                }
            },
        )

        addMouseListener(
            object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (cancelButtonBounds?.contains(e.point) == true) {
                        onCancelCallback?.invoke()
                    }
                    if (actionButtonBounds?.contains(e.point) == true) {
                        onActionCallback?.invoke()
                    }
                }

                override fun mouseExited(e: MouseEvent) {
                    if (isCancelButtonHovered || isActionButtonHovered) {
                        isCancelButtonHovered = false
                        isActionButtonHovered = false
                        cursor = Cursor.getDefaultCursor()
                        repaint()
                    }
                }
            },
        )
    }

    fun setOnCancelCallback(callback: () -> Unit) {
        onCancelCallback = callback
    }

    fun showActionButton(text: String, callback: () -> Unit) {
        actionButtonText = text
        onActionCallback = callback
        repaint()
    }

    fun hideActionButton() {
        actionButtonText = null
        onActionCallback = null
        actionButtonBounds = null
        isActionButtonHovered = false
        repaint()
    }

    private var isCancelledState = false

    /** Shows a message and retry button without a spinner. */
    fun showCancelledState(message: String, retryCallback: () -> Unit) {
        isCancelledState = true
        isLoading = false
        isVisible = true
        currentText = message
        textOpacity = 1f
        showActionButton("Retry", retryCallback)
        cancelButtonBounds = null
        repaint()
    }

    private fun clearCancelledState() {
        if (isCancelledState) {
            isCancelledState = false
            hideActionButton()
        }
    }

    /** Updates status text without restarting an active fade-in. */
    fun showStatus(text: String) {
        clearCancelledState()
        if (!isLoading) {
            isLoading = true
            isVisible = true
            showStartTime = System.currentTimeMillis()
            currentText = text
            textOpacity = 0f
            textUpdateTime = System.currentTimeMillis()
            startAnimation()
        } else if (text != currentText) {
            currentText = text
            repaint()
        }
    }

    /** Fades out after the minimum display time has elapsed. */
    fun hideOverlay() {
        if (isLoading) {
            isLoading = false
            val displayedTime = System.currentTimeMillis() - showStartTime
            val remainingMinTime = (minDisplayTime - displayedTime).coerceAtLeast(0)
            textUpdateTime = System.currentTimeMillis() - fadeOutDelay + remainingMinTime
            startAnimation()
        }
    }

    fun hideImmediately() {
        fadeTimer?.stop()
        fadeTimer = null
        isLoading = false
        isVisible = false
        currentText = ""
        textOpacity = 0f
        showStartTime = 0
    }

    private fun startAnimation() {
        fadeTimer?.stop()
        fadeTimer =
            Timer(16) {
                updateAnimation()
            }
        fadeTimer?.start()
    }

    private fun updateAnimation() {
        val elapsed = System.currentTimeMillis() - textUpdateTime
        var needsRepaint = false

        if (isLoading) {
            if (textOpacity < 1f) {
                textOpacity = (elapsed.toFloat() / fadeInDuration).coerceIn(0f, 1f)
                needsRepaint = true
            }

            // Keep repainting after the text fade completes to animate the spinner.
            if (textOpacity >= 1f) {
                needsRepaint = true
            }
        } else {
            val fadeOutElapsed = elapsed - fadeOutDelay
            if (fadeOutElapsed > 0) {
                val fadeProgress = (fadeOutElapsed.toFloat() / fadeOutDuration).coerceIn(0f, 1f)
                textOpacity = 1f - fadeProgress
                needsRepaint = true

                if (fadeProgress >= 1f) {
                    fadeTimer?.stop()
                    isVisible = false
                    currentText = ""
                }
            }
        }

        if (needsRepaint) {
            repaint()
        }
    }

    override fun paintComponent(g: Graphics) {
        if (!isVisible || textOpacity <= 0f) {
            return
        }

        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.setRenderingHint(
            RenderingHints.KEY_TEXT_ANTIALIASING,
            RenderingHints.VALUE_TEXT_ANTIALIAS_ON,
        )

        val overlayAlpha = (textOpacity.coerceAtLeast(0f) * 0.85f).coerceIn(0f, 1f)
        g2.color =
            Color(
                overlayColor.red,
                overlayColor.green,
                overlayColor.blue,
                (overlayAlpha * 220).toInt(),
            )

        // Cut transparent windows around components that remain clickable.
        val overlayArea = java.awt.geom.Area(Rectangle(0, 0, width, height))
        val overlayLocationOnScreen =
            try {
                locationOnScreen
            } catch (_: Exception) {
                null
            }

        if (overlayLocationOnScreen != null) {
            for (component in excludedComponents) {
                try {
                    val compLocation = component.locationOnScreen
                    val relativeX = compLocation.x - overlayLocationOnScreen.x
                    val relativeY = compLocation.y - overlayLocationOnScreen.y
                    val padding = 2
                    val cornerRadius = 12
                    val windowShape =
                        RoundRectangle2D.Float(
                            (relativeX - padding).toFloat(),
                            (relativeY - padding).toFloat(),
                            (component.width + padding * 2).toFloat(),
                            (component.height + padding * 2).toFloat(),
                            cornerRadius.toFloat(),
                            cornerRadius.toFloat(),
                        )
                    overlayArea.subtract(java.awt.geom.Area(windowShape))
                } catch (_: Exception) {}
            }
        }

        g2.fill(overlayArea)

        val borderPulse = (System.currentTimeMillis() % 1500) / 1500.0
        val borderIntensity = 0.8 + 0.2 * sin(borderPulse * Math.PI * 2)
        val borderAlpha = (overlayAlpha * 255 * borderIntensity).toInt().coerceIn(0, 255)
        g2.color = Color(accentColor.red, accentColor.green, accentColor.blue, borderAlpha)
        g2.fillRect(0, 0, width, 4)

        spinner.draw(g2, width / 2, height / 2 - 70, overlayAlpha)

        g2.font = Font("Segoe UI", Font.PLAIN, 15)
        val metrics = g2.fontMetrics

        if (currentText.isNotEmpty() && textOpacity > 0f) {
            g2.color =
                Color(
                    textColor.red,
                    textColor.green,
                    textColor.blue,
                    (textOpacity * 255).toInt(),
                )
            val lines = currentText.split("\n")
            val lineHeight = metrics.height
            val startY = height / 2 + 55 - ((lines.size - 1) * lineHeight / 2)
            for ((index, line) in lines.withIndex()) {
                val lineWidth = metrics.stringWidth(line)
                val lineX = (width - lineWidth) / 2
                val lineY = startY + index * lineHeight
                g2.drawString(line, lineX, lineY)
            }
        }

        val textLineCount = if (currentText.isNotEmpty()) currentText.split("\n").size else 1
        val textHeightOffset = if (textLineCount > 1) (textLineCount - 1) * metrics.height / 2 else 0
        var actionButtonHeight = 0
        if (actionButtonText != null) {
            actionButtonHeight = drawActionButton(g2, overlayAlpha, textHeightOffset)
        } else {
            actionButtonBounds = null
        }

        if (isLoading && shouldShowCancelButton()) {
            drawCancelButton(g2, overlayAlpha, actionButtonHeight)
        } else {
            cancelButtonBounds = null
        }

        g2.dispose()
    }

    private fun shouldShowCancelButton(): Boolean =
        isCancellable && System.currentTimeMillis() - showStartTime >= cancelButtonShowDelay

    fun setCancellable(cancellable: Boolean) {
        isCancellable = cancellable
        if (!cancellable) {
            cancelButtonBounds = null
        }
        repaint()
    }

    /** Allows mouse events over this component to pass through the overlay. */
    fun addExcludedComponent(component: Component) {
        if (component !in excludedComponents) {
            excludedComponents.add(component)
        }
    }

    private fun drawCancelButton(g2: Graphics2D, alpha: Float, verticalOffset: Int = 0) {
        val buttonText = "Cancel"
        g2.font = Font("Segoe UI", Font.PLAIN, 13)
        val metrics = g2.fontMetrics
        val textWidth = metrics.stringWidth(buttonText)
        val textHeight = metrics.height

        val buttonWidth = textWidth + 24
        val buttonHeight = textHeight + 10
        val buttonX = (width - buttonWidth) / 2
        val buttonY = height / 2 + 85 + verticalOffset

        cancelButtonBounds = Rectangle(buttonX, buttonY, buttonWidth, buttonHeight)

        val buttonColor = if (isCancelButtonHovered) cancelButtonHoverColor else cancelButtonColor
        val buttonAlpha = (alpha * 255).toInt()
        g2.color = Color(buttonColor.red, buttonColor.green, buttonColor.blue, buttonAlpha)
        g2.fill(
            RoundRectangle2D.Float(
                buttonX.toFloat(),
                buttonY.toFloat(),
                buttonWidth.toFloat(),
                buttonHeight.toFloat(),
                6f,
                6f,
            ),
        )

        g2.color = Color(accentColor.red, accentColor.green, accentColor.blue, (alpha * 150).toInt())
        g2.draw(
            RoundRectangle2D.Float(
                buttonX.toFloat(),
                buttonY.toFloat(),
                buttonWidth.toFloat(),
                buttonHeight.toFloat(),
                6f,
                6f,
            ),
        )

        g2.color =
            Color(
                cancelButtonTextColor.red,
                cancelButtonTextColor.green,
                cancelButtonTextColor.blue,
                buttonAlpha,
            )
        val textX = buttonX + (buttonWidth - textWidth) / 2
        val textY = buttonY + (buttonHeight + metrics.ascent - metrics.descent) / 2
        g2.drawString(buttonText, textX, textY)
    }

    /** Returns the button height including spacing, or zero when absent. */
    private fun drawActionButton(g2: Graphics2D, alpha: Float, textHeightOffset: Int = 0): Int {
        val buttonText = actionButtonText ?: return 0
        g2.font = Font("Segoe UI", Font.BOLD, 14)
        val metrics = g2.fontMetrics
        val textWidth = metrics.stringWidth(buttonText)
        val textHeight = metrics.height

        val buttonWidth = textWidth + 32
        val buttonHeight = textHeight + 14
        val buttonX = (width - buttonWidth) / 2
        val buttonY = height / 2 + 85 + textHeightOffset

        actionButtonBounds = Rectangle(buttonX, buttonY, buttonWidth, buttonHeight)

        val buttonAlpha = (alpha * 255).toInt()
        val bgColor =
            if (isActionButtonHovered) {
                Color(
                    accentColor.red.coerceAtMost(255),
                    accentColor.green.coerceAtMost(255),
                    (accentColor.blue + 30).coerceAtMost(255),
                    buttonAlpha,
                )
            } else {
                Color(accentColor.red, accentColor.green, accentColor.blue, buttonAlpha)
            }
        g2.color = bgColor
        g2.fill(
            RoundRectangle2D.Float(
                buttonX.toFloat(),
                buttonY.toFloat(),
                buttonWidth.toFloat(),
                buttonHeight.toFloat(),
                8f,
                8f,
            ),
        )

        g2.color = Color(255, 255, 255, (alpha * 80).toInt())
        g2.draw(
            RoundRectangle2D.Float(
                buttonX.toFloat(),
                buttonY.toFloat(),
                buttonWidth.toFloat(),
                buttonHeight.toFloat(),
                8f,
                8f,
            ),
        )

        g2.color = Color(255, 255, 255, buttonAlpha)
        val textX = buttonX + (buttonWidth - textWidth) / 2
        val textY = buttonY + (buttonHeight + metrics.ascent - metrics.descent) / 2
        g2.drawString(buttonText, textX, textY)

        return buttonHeight + 10
    }

    private val spinner = LoadingMeshSpinner(accentColor)

    // Intercept clicks except over excluded components.
    override fun contains(x: Int, y: Int): Boolean {
        if (!isVisible) return false

        val overlayLocationOnScreen =
            try {
                locationOnScreen
            } catch (_: Exception) {
                return true
            }

        for (component in excludedComponents) {
            val componentLocationOnScreen =
                try {
                    component.locationOnScreen
                } catch (_: Exception) {
                    continue
                }

            val relativeX = overlayLocationOnScreen.x + x - componentLocationOnScreen.x
            val relativeY = overlayLocationOnScreen.y + y - componentLocationOnScreen.y

            if (relativeX >= 0 && relativeX < component.width && relativeY >= 0 && relativeY < component.height) {
                return false
            }
        }

        return true
    }
}
