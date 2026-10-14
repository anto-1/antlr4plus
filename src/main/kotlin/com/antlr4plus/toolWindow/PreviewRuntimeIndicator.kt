package com.antlr4plus.toolWindow

import com.antlr4plus.icons.Icons
import com.antlr4plus.previewService.PreviewService
import com.antlr4plus.state.PreviewState
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JLabel

internal class PreviewRuntimeIndicator(previewService: PreviewService, project: Project) : JLabel(Icons.JAVA_RUNTIME) {
    private var showPendingBadge = false
    private var confirmedRuntime: String = previewService.state.liveSettings?.previewRuntime ?: "Java"
    private var pendingRuntime: String? = null

    init {
        preferredSize = Dimension(ToolWindowTheme.BUTTON_SIZE, ToolWindowTheme.BUTTON_SIZE)
        minimumSize = Dimension(ToolWindowTheme.BUTTON_SIZE, ToolWindowTheme.BUTTON_SIZE)
        maximumSize = Dimension(ToolWindowTheme.BUTTON_SIZE, ToolWindowTheme.BUTTON_SIZE)
        horizontalAlignment = CENTER
        verticalAlignment = CENTER
        toolTipText = "Preview Runtime: Java (click to change)"
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        previewService.state.liveSettings?.let { settings ->
            val runtime = settings.previewRuntime ?: "Java"
            icon = if (runtime == "Kotlin") Icons.KOTLIN_RUNTIME else Icons.JAVA_RUNTIME
            toolTipText = "Preview Runtime: $runtime (click to change)"
        }
        // Runtime settings remain accessible before a grammar is bound.
        addMouseListener(
            object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (previewService.state.g4pInputFile == null) {
                        return
                    }
                    project.messageBus.syncPublisher(PreviewService.SWITCH_PANE_TOPIC).onSwitchPane("Settings")
                    project.messageBus.syncPublisher(PreviewService.RUNTIME_HIGHLIGHT_TOPIC).onHighlightRuntime()
                }
            },
        )
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)

        if (showPendingBadge) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)

            val iconSize = 12
            val iconX = width - iconSize - 2
            val iconY = 2

            val strokeColor = if (JBColor.isBright()) Color(0x6C, 0x70, 0x7E) else Color(0xCE, 0xD0, 0xD6)

            g2.color = strokeColor
            g2.stroke = BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)

            g2.drawLine(iconX, iconY, iconX + iconSize, iconY)
            g2.drawLine(iconX, iconY + iconSize, iconX + iconSize, iconY + iconSize)
            g2.drawLine(iconX, iconY, iconX + iconSize / 2, iconY + iconSize / 2)
            g2.drawLine(iconX + iconSize, iconY, iconX + iconSize / 2, iconY + iconSize / 2)
            g2.drawLine(iconX + iconSize / 2, iconY + iconSize / 2, iconX, iconY + iconSize)
            g2.drawLine(
                iconX + iconSize / 2,
                iconY + iconSize / 2,
                iconX + iconSize,
                iconY + iconSize,
            )

            g2.dispose()
        }
    }

    fun showPendingRuntime(newRuntime: String) {
        pendingRuntime = newRuntime
        icon = if (newRuntime == "Kotlin") Icons.KOTLIN_RUNTIME else Icons.JAVA_RUNTIME
        val isActualChange = newRuntime != confirmedRuntime
        showPendingBadge = isActualChange
        toolTipText =
            if (isActualChange) {
                "Preview Runtime: $newRuntime (processing...)"
            } else {
                "Preview Runtime: $newRuntime (click to change)"
            }
        repaint()
    }

    fun confirmRuntime(previewState: PreviewState) {
        previewState.liveSettings?.let { settings ->
            val runtime = settings.previewRuntime ?: "Java"
            icon = if (runtime == "Kotlin") Icons.KOTLIN_RUNTIME else Icons.JAVA_RUNTIME
            toolTipText = "Preview Runtime: $runtime (click to change)"
            confirmedRuntime = runtime
            pendingRuntime = null
            showPendingBadge = false
            repaint()
        }
    }

    fun finishPendingRuntime() {
        val runtime = pendingRuntime ?: return
        confirmedRuntime = runtime
        pendingRuntime = null
        showPendingBadge = false
        toolTipText = "Preview Runtime: $runtime (click to change)"
        repaint()
    }
}
