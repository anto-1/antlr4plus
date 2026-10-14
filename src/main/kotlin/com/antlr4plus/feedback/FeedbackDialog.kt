package com.antlr4plus.feedback

import com.antlr4plus.notifications.NotificationsUtils
import com.intellij.ide.BrowserUtil
import com.intellij.ide.plugins.cl.PluginAwareClassLoader
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.util.ui.JBUI
import java.awt.Component
import java.awt.Cursor
import java.awt.GridLayout
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import javax.swing.*

class FeedbackDialog(private val project: Project?) : DialogWrapper(project) {

    init {
        title = "ANTLR4+ Feedback"
        init()
        isResizable = false
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.border = JBUI.Borders.empty(16, 20, 12, 20)

        val message =
            JLabel(
                "<html><body style='width: 340px'>" +
                    "<p>This plugin doesn't collect any usage data.</p>" +
                    "<p style='margin-top:8px'>If something doesn't work or feels off, " +
                    "a quick review or bug report helps more than you'd think " +
                    "&mdash; I read every one and bugs are typically fixed within days.</p>" +
                    "</body></html>",
            )
        message.alignmentX = Component.LEFT_ALIGNMENT
        panel.add(message)

        panel.add(Box.createVerticalStrut(16))

        val buttonPanel = JPanel(GridLayout(3, 1, 0, 6))
        buttonPanel.alignmentX = Component.LEFT_ALIGNMENT

        buttonPanel.add(
            createActionButton(
                "\u2709  Something broke \u2014 Report on Marketplace (template copied)",
            ) {
                val pluginVersion =
                    (FeedbackDialog::class.java.classLoader as? PluginAwareClassLoader)?.pluginDescriptor?.version
                        ?: "unknown"
                val ideVersion = ApplicationInfo.getInstance().fullVersion
                val os = System.getProperty("os.name") + " " + System.getProperty("os.version")

                val body =
                    """
                |**Describe the issue:**
                |
                |
                |**Steps to reproduce:**
                |1.
                |
                |**Expected behavior:**
                |
                |
                |---
                |Plugin: $pluginVersion
                |IDE: $ideVersion
                |OS: $os
            """
                        .trimMargin()

                val clipboard = Toolkit.getDefaultToolkit().systemClipboard
                clipboard.setContents(StringSelection(body), null)
                close(OK_EXIT_CODE)
                BrowserUtil.browse("https://plugins.jetbrains.com/plugin/29781-antlr4-plus/reviews")
                project?.let {
                    NotificationsUtils.showToast(
                        it,
                        "Bug report template copied to clipboard. Paste it in your review.",
                    )
                }
            },
        )

        buttonPanel.add(
            createActionButton("\u2b50  Leave a review on JetBrains Marketplace") {
                close(OK_EXIT_CODE)
                BrowserUtil.browse("https://plugins.jetbrains.com/plugin/29781-antlr4-plus/reviews")
            },
        )

        buttonPanel.add(
            createActionButton("\u2709  Copy email address (i00@tutanota.com)") {
                val clipboard = Toolkit.getDefaultToolkit().systemClipboard
                clipboard.setContents(StringSelection("i00@tutanota.com"), null)
                close(OK_EXIT_CODE)
                project?.let {
                    NotificationsUtils.showToast(it, "i00@tutanota.com copied to clipboard")
                }
            },
        )

        panel.add(buttonPanel)

        return panel
    }

    private fun createActionButton(text: String, action: () -> Unit): JButton =
        JButton(text).apply {
            horizontalAlignment = SwingConstants.LEFT
            font = font.deriveFont(font.size + 1f)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            isFocusPainted = false
            margin = JBUI.insets(8, 12)
            addActionListener { action() }
        }

    override fun createActions() = arrayOf(cancelAction)

    override fun getCancelAction(): Action =
        super.getCancelAction().also { action ->
            action.putValue(Action.NAME, "Close")
        }
}
