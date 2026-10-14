package com.antlr4plus.toolWindow

import com.antlr4plus.error.ComposeAntlrErrorConsolePanel
import com.antlr4plus.error.ComposeCompilerConsolePanel
import com.antlr4plus.error.ComposeParsingErrorConsolePanel
import com.antlr4plus.previewService.PreviewService
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.ui.JBColor
import com.intellij.ui.components.OnOffButton
import java.awt.AlphaComposite
import java.awt.CardLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.ButtonGroup
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JToggleButton
import javax.swing.SwingConstants
import javax.swing.UIManager
import javax.swing.plaf.basic.BasicToggleButtonUI

internal fun createIconButton(
    paneName: String,
    icon: Icon,
    cardPanel: JPanel,
    previewService: PreviewService,
    buttonGroup: ButtonGroup,
    titleManager: ToolWindowTitleManager,
    fadingToast: FadingToast? = null,
    exemptFromPluginDisable: Boolean = false,
): JToggleButton {
    val button =
        object : JToggleButton(icon) {
            private var isHovered = false

            init {
                addMouseListener(
                    object : MouseAdapter() {
                        override fun mouseEntered(e: MouseEvent) {
                            isHovered = true
                            repaint()
                        }

                        override fun mouseExited(e: MouseEvent) {
                            isHovered = false
                            repaint()
                        }
                    },
                )
            }

            override fun paintComponent(g: Graphics) {
                val isPluginDisabled = getClientProperty("isPluginDisabled") == true
                val isOverlayLoading = getClientProperty("isOverlayLoading") == true
                val isActive =
                    !isPluginDisabled &&
                        !isOverlayLoading &&
                        getClientProperty("isPaneButton") == true &&
                        getClientProperty("activePane") == true
                val showHover = isHovered && !isPluginDisabled && !isOverlayLoading
                ToolWindowTheme.drawPaneButtonBackground(g, width, height, isActive, showHover)

                super.paintComponent(g)

                // Dim logically disabled buttons while retaining clicks for explanatory toasts.
                val isLogicallyDisabled = getClientProperty("isLogicallyDisabled") == true
                val shouldGreyOut =
                    isLogicallyDisabled || (isPluginDisabled && getClientProperty("exemptFromPluginDisable") != true)
                if (shouldGreyOut) {
                    paintDisabledOverlay(g)
                }
            }
        }

    button.toolTipText = paneName
    button.configurePaneButton(buttonGroup)

    button.addActionListener {
        if (button.getClientProperty("isLogicallyDisabled") == true) {
            if (previewService.isEnabled) {
                fadingToast?.showMessage("Requires Parser Grammar")
            }
            return@addActionListener
        }

        button.showPane(paneName, cardPanel, previewService, titleManager)
    }

    button.putClientProperty("isPaneButton", true)
    if (exemptFromPluginDisable) {
        button.putClientProperty("exemptFromPluginDisable", true)
    }

    return button
}

internal fun createBadgedIconButton(
    paneName: String,
    icon: Icon,
    cardPanel: JPanel,
    previewService: PreviewService,
    buttonGroup: ButtonGroup,
    consolePanel: Any,
    titleManager: ToolWindowTitleManager,
): JToggleButton {
    val button =
        object : JToggleButton(icon) {
            private var isHovered = false

            init {
                addMouseListener(
                    object : MouseAdapter() {
                        override fun mouseEntered(e: MouseEvent) {
                            isHovered = true
                            repaint()
                        }

                        override fun mouseExited(e: MouseEvent) {
                            isHovered = false
                            repaint()
                        }
                    },
                )
            }

            override fun paintComponent(g: Graphics) {
                val isPluginDisabled = getClientProperty("isPluginDisabled") == true
                val isOverlayLoading = getClientProperty("isOverlayLoading") == true
                val isActive =
                    !isPluginDisabled &&
                        !isOverlayLoading &&
                        getClientProperty("isPaneButton") == true &&
                        getClientProperty("activePane") == true
                val showHover = isHovered && !isPluginDisabled && !isOverlayLoading
                ToolWindowTheme.drawPaneButtonBackground(g, width, height, isActive, showHover)

                super.paintComponent(g)

                if (isPluginDisabled) {
                    paintDisabledOverlay(g)
                }
            }

            // Paint the badge after children so it remains visible.
            override fun paint(g: Graphics) {
                super.paint(g)

                val count = consoleLineCount(consolePanel)

                if (count > 0) {
                    val g2 = g.create() as Graphics2D
                    g2.setRenderingHint(
                        RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON,
                    )
                    g2.setRenderingHint(
                        RenderingHints.KEY_TEXT_ANTIALIASING,
                        RenderingHints.VALUE_TEXT_ANTIALIAS_ON,
                    )

                    val badgeText = if (count > 9) "9+" else count.toString()
                    g2.font = Font("Arial", Font.BOLD, ToolWindowTheme.BADGE_FONT_SIZE)
                    val metrics = g2.getFontMetrics(g2.font)
                    val textWidth = metrics.stringWidth(badgeText)
                    val badgeWidth = maxOf(textWidth + 6, ToolWindowTheme.BADGE_SIZE)
                    val badgeHeight = ToolWindowTheme.BADGE_SIZE

                    val badgeX = width - badgeWidth - 2
                    val badgeY = 2

                    g2.color = JBColor(Color(0xE74C3C), Color(0xE74C3C))
                    g2.fillRoundRect(
                        badgeX,
                        badgeY,
                        badgeWidth,
                        badgeHeight,
                        badgeHeight,
                        badgeHeight,
                    )

                    g2.color = Color.WHITE
                    val textX = badgeX + (badgeWidth - textWidth) / 2
                    val textY = badgeY + ((badgeHeight - metrics.height) / 2) + metrics.ascent
                    g2.drawString(badgeText, textX, textY)

                    g2.dispose()
                }
            }
        }

    button.toolTipText = "$paneName (${consoleLineCount(consolePanel)} items)"
    button.configurePaneButton(buttonGroup)

    button.addActionListener {
        button.showPane(paneName, cardPanel, previewService, titleManager)
    }

    button.putClientProperty("isPaneButton", true)

    return button
}

private fun JToggleButton.configurePaneButton(buttonGroup: ButtonGroup) {
    preferredSize = Dimension(ToolWindowTheme.BUTTON_SIZE, ToolWindowTheme.BUTTON_SIZE)
    minimumSize = Dimension(ToolWindowTheme.BUTTON_SIZE, ToolWindowTheme.BUTTON_SIZE)
    maximumSize = Dimension(ToolWindowTheme.BUTTON_SIZE, ToolWindowTheme.BUTTON_SIZE)
    isFocusable = false
    setUI(BasicToggleButtonUI())
    isOpaque = false
    isContentAreaFilled = false
    isBorderPainted = false
    border = BorderFactory.createEmptyBorder()
    horizontalAlignment = SwingConstants.CENTER
    verticalAlignment = SwingConstants.CENTER

    buttonGroup.add(this)
}

private fun JToggleButton.showPane(
    paneName: String,
    cardPanel: JPanel,
    previewService: PreviewService,
    titleManager: ToolWindowTitleManager,
) {
    val layout = cardPanel.layout as CardLayout
    layout.show(cardPanel, paneName)

    titleManager.setTitle(ToolWindowTitle.build(paneName, previewService.state))

    (parent as? JPanel)?.components?.forEach { comp ->
        if (comp is JToggleButton && comp.getClientProperty("isPaneButton") == true) {
            comp.putClientProperty("activePane", false)
            comp.repaint()
        }
    }

    putClientProperty("activePane", true)
    repaint()

    handlePaneSelection(paneName, previewService)
}

internal fun createActionButton(
    tooltipText: String,
    icon: Icon,
    action: () -> Unit,
): JButton =
    JButton(icon).apply {
        toolTipText = tooltipText
        preferredSize = Dimension(ToolWindowTheme.BUTTON_SIZE, ToolWindowTheme.BUTTON_SIZE)
        minimumSize = Dimension(ToolWindowTheme.BUTTON_SIZE, ToolWindowTheme.BUTTON_SIZE)
        maximumSize = Dimension(ToolWindowTheme.BUTTON_SIZE, ToolWindowTheme.BUTTON_SIZE)
        isFocusable = false
        isBorderPainted = false
        isContentAreaFilled = false
        addActionListener { action() }
    }

internal fun createGlobalToggle(
    previewService: PreviewService,
    loadingPanel: LoadingOverlayPanel,
): OnOffButton =
    OnOffButton().apply {
        toolTipText = "Enable/Disable preview generation"
        isSelected = previewService.isEnabled

        val scale = 1.25
        val defaultWidth = preferredSize.width
        val defaultHeight = preferredSize.height
        val newSize = Dimension((defaultWidth * scale).toInt(), (defaultHeight * scale).toInt())
        preferredSize = newSize
        minimumSize = newSize
        maximumSize = newSize

        addActionListener {
            previewService.isEnabled = isSelected
            if (!isSelected) {
                previewService.cancelCurrentJob()
                loadingPanel.hideLoadingStatus()
            }
        }
    }

private fun handlePaneSelection(paneName: String, previewService: PreviewService) {
    previewService.activePane = paneName

    if (paneName == "Lexer") {
        val panel = previewService.tokenTimelinePanel
        val ps = previewService.project.getService(com.antlr4plus.settings.ProjectSettingsService::class.java).state
        if (ps.colorizeTokens && panel != null && !panel.isHighlightingActive()) {
            panel.enableHighlighting()
        }
    }

    if (paneName != "Lexer") {
        previewService.tokenTimelinePanel?.stopBreathing()
        val g4pFile = previewService.state.g4pInputFile
        if (g4pFile != null) {
            val fem = FileEditorManager.getInstance(previewService.project)
            for (fe in fem.getEditors(g4pFile)) {
                if (fe is com.intellij.openapi.fileEditor.TextEditor) {
                    fe.editor.markupModel.allHighlighters
                        .filter { it.getUserData(PreviewService.CARET_TOKEN_HIGHLIGHT_KEY) == true }
                        .forEach { fe.editor.markupModel.removeHighlighter(it) }
                }
            }
        }
    }

    previewService.uber?.resetCursorFollowMode()
    previewService.hierarchy?.resetCursorFollowMode()

    if (paneName != "Profiler") {
        runReadAction {
            previewService.composeProfilerPanel?.clearSelectionAndHighlights()
        }
    }

    if (paneName != "Tree") {
        previewService.state.g4pInputFile?.let {
            runReadAction {
                previewService.hierarchy?.selectionPath?.let {}
            }
        }
    }
}

internal fun createAutoRefreshToggle(
    previewService: PreviewService,
    isProgrammaticUpdate: () -> Boolean,
): JToggleButton =
    object : JToggleButton() {
            override fun paintComponent(g: Graphics) {
                super.paintComponent(g)

                if (!isEnabled) {
                    paintDisabledOverlay(g)
                }
            }
        }
        .apply {
            icon = AllIcons.Actions.Pause
            selectedIcon = AllIcons.Actions.Resume
            toolTipText = "Pause automatic refresh"
            isSelected = previewService.isAutoRefreshPaused
            isFocusPainted = false
            isBorderPainted = false
            isContentAreaFilled = false

            val size = Dimension(ToolWindowTheme.BUTTON_SIZE, ToolWindowTheme.BUTTON_SIZE)
            preferredSize = size
            minimumSize = size
            maximumSize = size

            addActionListener {
                if (isProgrammaticUpdate()) return@addActionListener
                previewService.isAutoRefreshPaused = isSelected
                toolTipText = if (isSelected) "Resume automatic refresh" else "Pause automatic refresh"
            }
        }

private fun JToggleButton.paintDisabledOverlay(g: Graphics) {
    val g2 = g.create() as Graphics2D
    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, ToolWindowTheme.DISABLED_OVERLAY_ALPHA)
    g2.color = UIManager.getColor("Panel.background") ?: JBColor.background()
    g2.fillRoundRect(
        0,
        0,
        width,
        height,
        ToolWindowTheme.BORDER_RADIUS,
        ToolWindowTheme.BORDER_RADIUS,
    )
    g2.dispose()
}

private fun consoleLineCount(consolePanel: Any): Int =
    when (consolePanel) {
        is ComposeAntlrErrorConsolePanel -> consolePanel.getLineCount()
        is ComposeParsingErrorConsolePanel -> consolePanel.getLineCount()
        is ComposeCompilerConsolePanel -> consolePanel.getLineCount()
        is com.antlr4plus.error.ComposeRuntimeConsolePanel -> consolePanel.getLineCount()
        else -> 0
    }
