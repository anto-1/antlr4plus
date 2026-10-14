package com.antlr4plus.onboarding

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.*
import javax.swing.*

class OnboardingDialog(
    project: Project?,
) : DialogWrapper(project) {

    private val pages = createOnboardingPages(project)
    private var currentPageIndex = 0
    private var currentSequence: TypewriterSequence? = null
    private var highlightBoxAdded = false

    private lateinit var contentPanel: JPanel
    private lateinit var pageIndicatorPanel: JPanel

    init {
        title = "Welcome to ANTLR4+"
        setOKButtonText("Get Started")
        setCancelButtonText("Skip")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val mainPanel =
            JPanel(BorderLayout()).apply {
                preferredSize = Dimension(1000, 476)
                border = JBUI.Borders.empty(20)
            }

        contentPanel =
            JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                background = JBColor.background()
                border = JBUI.Borders.empty(10)
            }

        val scrollPane =
            JBScrollPane(contentPanel).apply {
                border = null
                verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
                horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            }

        mainPanel.add(scrollPane, BorderLayout.CENTER)

        val bottomPanel =
            JPanel(BorderLayout()).apply {
                border = JBUI.Borders.emptyTop(15)
            }

        pageIndicatorPanel =
            JPanel(FlowLayout(FlowLayout.CENTER, JBUI.scale(16), 0)).apply {
                isOpaque = false
            }
        updatePageIndicator()

        val skipButton =
            JButton("Skip animation").apply {
                isBorderPainted = false
                isContentAreaFilled = false
                foreground = JBColor.GRAY
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                addActionListener { skipCurrentAnimation() }
            }

        bottomPanel.add(skipButton, BorderLayout.WEST)
        bottomPanel.add(pageIndicatorPanel, BorderLayout.CENTER)

        mainPanel.add(bottomPanel, BorderLayout.SOUTH)

        showPage(0)

        return mainPanel
    }

    override fun createActions(): Array<Action> = arrayOf(cancelAction, okAction)

    override fun createLeftSideActions(): Array<Action> =
        arrayOf(
            object : DialogWrapperAction("Previous") {
                override fun doAction(e: java.awt.event.ActionEvent?) {
                    if (currentPageIndex > 0) {
                        showPage(currentPageIndex - 1)
                    }
                }
            },
        )

    override fun doOKAction() {
        if (currentPageIndex < pages.size - 1) {
            showPage(currentPageIndex + 1)
        } else {
            currentSequence?.dispose()
            OnboardingManager.markOnboardingComplete()
            super.doOKAction()
        }
    }

    override fun doCancelAction() {
        currentSequence?.dispose()
        OnboardingManager.markOnboardingComplete()
        super.doCancelAction()
    }

    private fun showPage(index: Int, animate: Boolean = true) {
        currentPageIndex = index
        currentSequence?.dispose()
        highlightBoxAdded = false

        contentPanel.removeAll()

        val page = pages[index]

        val titleLabel =
            TypewriterLabel(page.title, charDelayMs = 12, initialDelayMs = 50).apply {
                font = font.deriveFont(Font.BOLD).deriveFont(JBUI.scaleFontSize(18f))
                foreground = JBColor.foreground()
                alignmentX = Component.LEFT_ALIGNMENT
                border = JBUI.Borders.emptyBottom(15)
            }
        contentPanel.add(titleLabel)

        val contentLabels =
            page.content.map { line ->
                TypewriterLabel(line, charDelayMs = 10, initialDelayMs = 0).apply {
                    alignmentX = Component.LEFT_ALIGNMENT
                    border = JBUI.Borders.emptyBottom(8)
                }
            }

        contentLabels.forEach { contentPanel.add(it) }

        if (!animate) {
            titleLabel.skipAnimation()
            contentLabels.forEach { it.skipAnimation() }

            page.highlight?.let { highlight ->
                highlightBoxAdded = true
                contentPanel.add(Box.createVerticalStrut(JBUI.scale(10)))
                val highlightPanel = createHighlightBox(highlight, page.highlightActions)
                highlightPanel.showImmediately()
                contentPanel.add(highlightPanel)
            }

            contentPanel.add(Box.createVerticalGlue())
            contentPanel.revalidate()
            contentPanel.repaint()
            updatePageIndicator()
            updateButtonText()
            return
        }

        contentPanel.add(Box.createVerticalGlue())
        contentPanel.revalidate()
        contentPanel.repaint()

        val allLabels = mutableListOf(titleLabel)
        allLabels.addAll(contentLabels)
        currentSequence = TypewriterSequence(allLabels, delayBetweenMs = 100)
        currentSequence?.startSequence {
            if (currentPageIndex != index || highlightBoxAdded) return@startSequence

            page.highlight?.let { highlight ->
                highlightBoxAdded = true
                val glueIndex = contentPanel.componentCount - 1
                contentPanel.add(Box.createVerticalStrut(JBUI.scale(10)), glueIndex)
                val highlightPanel = createHighlightBox(highlight, page.highlightActions)
                contentPanel.add(highlightPanel, glueIndex + 1)
                contentPanel.revalidate()
                contentPanel.repaint()
                highlightPanel.fadeIn()
            }
            updateButtonText()
        }

        updatePageIndicator()
        updateButtonText()
    }

    private fun createHighlightBox(
        text: String,
        actions: List<HighlightAction> = emptyList(),
    ): FadeInPanel {
        val bgColor =
            JBColor(
                Color(0xA6, 0xD2, 0xFF),
                Color((0xA6 * 0.4).toInt(), (0xD2 * 0.4).toInt(), (0xFF * 0.4).toInt()),
            )
        val borderColor =
            JBColor(
                Color(0x5A, 0xA0, 0xE0),
                Color(0x4A, 0x80, 0xB0),
            )
        val cornerRadius = JBUI.scale(8)
        val hasActions = actions.isNotEmpty()

        return object : FadeInPanel(700) {
            init {
                layout = BorderLayout()
                isOpaque = false
                border = JBUI.Borders.empty(12)
                alignmentX = LEFT_ALIGNMENT
                maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(if (hasActions) 120 else 100))

                val textLabel =
                    JLabel(
                            "<html><body style='width: ${JBUI.scale(if (hasActions) 520 else 640)}px'>$text</body></html>",
                        )
                        .apply {
                            foreground = JBColor.foreground()
                            font = font.deriveFont(font.size * 1.30f)
                        }

                add(textLabel, BorderLayout.CENTER)

                if (hasActions) {
                    val buttonPanel =
                        JPanel().apply {
                            isOpaque = false
                            layout = BoxLayout(this, BoxLayout.Y_AXIS)
                        }
                    for ((label, action) in actions) {
                        buttonPanel.add(
                            JButton(label).apply {
                                addActionListener { action() }
                                alignmentX = CENTER_ALIGNMENT
                            },
                        )
                        buttonPanel.add(Box.createVerticalStrut(JBUI.scale(4)))
                    }
                    add(buttonPanel, BorderLayout.EAST)
                }
            }

            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(
                    RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON,
                )
                g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha)

                g2.color = bgColor
                g2.fillRoundRect(0, 0, width, height, cornerRadius, cornerRadius)

                g2.color = borderColor
                g2.drawRoundRect(0, 0, width - 1, height - 1, cornerRadius, cornerRadius)

                g2.dispose()
            }
        }
    }

    private open class FadeInPanel(private val durationMs: Int) : JPanel() {
        protected var alpha = 0f
        private var fadeTimer: Timer? = null

        fun showImmediately() {
            fadeTimer?.stop()
            alpha = 1f
            repaint()
        }

        fun fadeIn() {
            val steps = 30
            val delay = durationMs / steps
            var step = 0

            fadeTimer?.stop()
            fadeTimer =
                Timer(delay) { e ->
                    step++
                    alpha = (step.toFloat() / steps).coerceIn(0f, 1f)
                    repaint()

                    if (step >= steps) {
                        (e.source as Timer).stop()
                    }
                }
            fadeTimer?.start()
        }
    }

    private fun skipCurrentAnimation() {
        currentSequence?.skipAll()
    }

    private fun updatePageIndicator() {
        pageIndicatorPanel.removeAll()
        pages.indices.forEach { index ->
            val dot = createClickableDot(index)
            pageIndicatorPanel.add(dot)
        }
        pageIndicatorPanel.revalidate()
        pageIndicatorPanel.repaint()
    }

    private fun createClickableDot(index: Int): JLabel {
        val isActive = index == currentPageIndex
        return JLabel(if (isActive) "\u25CF" else "\u25CB").apply {
            font = font.deriveFont(JBUI.scaleFontSize(28f))
            foreground = if (isActive) JBColor.foreground() else JBColor.GRAY
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = "Page ${index + 1}: ${pages[index].title}"
            addMouseListener(
                object : java.awt.event.MouseAdapter() {
                    override fun mouseClicked(e: java.awt.event.MouseEvent?) {
                        if (index == currentPageIndex) {
                            skipCurrentAnimation()
                        } else {
                            showPage(index, animate = false)
                        }
                    }

                    override fun mouseEntered(e: java.awt.event.MouseEvent?) {
                        if (index != currentPageIndex) {
                            foreground = JBColor.foreground()
                        }
                    }

                    override fun mouseExited(e: java.awt.event.MouseEvent?) {
                        if (index != currentPageIndex) {
                            foreground = JBColor.GRAY
                        }
                    }
                },
            )
        }
    }

    private fun updateButtonText() =
        setOKButtonText(if (currentPageIndex < pages.size - 1) "Continue" else "Get Started")

    data class OnboardingPage(
        val title: String,
        val content: List<String>,
        val highlight: String? = null,
        val highlightAction: HighlightAction? = null,
        val highlightActions: List<HighlightAction> = listOfNotNull(highlightAction),
    )

    data class HighlightAction(
        val label: String,
        val action: () -> Unit,
    )

    companion object {
        fun showIfNeeded(project: Project?) {
            if (!OnboardingManager.hasCompletedOnboarding()) {
                OnboardingDialog(project).show()
            }
        }

        fun showAlways(project: Project?) {
            OnboardingDialog(project).show()
        }
    }
}
