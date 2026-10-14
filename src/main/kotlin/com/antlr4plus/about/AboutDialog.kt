package com.antlr4plus.about

import com.intellij.ide.BrowserUtil
import com.intellij.ide.plugins.cl.PluginAwareClassLoader
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.*
import javax.swing.*
import javax.swing.event.HyperlinkEvent
import javax.swing.text.html.HTMLEditorKit
import javax.swing.text.html.StyleSheet

class AboutDialog(project: Project?) : DialogWrapper(project) {

    init {
        title = "About ANTLR4+"
        setOKButtonText("Close")
        setCancelButtonText("")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val mainPanel =
            JPanel(BorderLayout()).apply {
                preferredSize = Dimension(520, 480)
                border = JBUI.Borders.empty(20)
            }

        val contentPanel =
            JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                background = JBColor.background()
            }

        val pluginDescriptor = (AboutDialog::class.java.classLoader as? PluginAwareClassLoader)?.pluginDescriptor
        val version = pluginDescriptor?.version ?: "Unknown"

        contentPanel.add(createHeader("ANTLR4+", version, "Antoni Tremblay"))
        contentPanel.add(Box.createVerticalStrut(JBUI.scale(15)))

        contentPanel.add(createAcknowledgmentsSection())
        contentPanel.add(Box.createVerticalStrut(JBUI.scale(12)))

        contentPanel.add(
            createSection(
                "Bundled",
                "ANTLR 4.13.2 • Eclipse ECJ 3.44.0 • Kotlin 2.3.0",
            ),
        )
        contentPanel.add(Box.createVerticalStrut(JBUI.scale(12)))

        contentPanel.add(createLicenseSection())
        contentPanel.add(Box.createVerticalStrut(JBUI.scale(12)))

        contentPanel.add(createLinksSection())

        val scrollPane =
            JBScrollPane(contentPanel).apply {
                border = null
                verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
                horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            }

        mainPanel.add(scrollPane, BorderLayout.CENTER)
        return mainPanel
    }

    override fun createActions(): Array<Action> = arrayOf(okAction)

    private fun createHeader(name: String, version: String, author: String): JPanel =
        JPanel(BorderLayout()).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT

            val nameLabel =
                JBLabel(name).apply {
                    font = font.deriveFont(Font.BOLD).deriveFont(JBUI.scaleFontSize(24f))
                    foreground = JBColor.foreground()
                }

            val versionLabel =
                JBLabel("Version $version").apply {
                    font = font.deriveFont(JBUI.scaleFontSize(14f))
                    foreground = JBColor.GRAY
                }

            val authorLabel =
                JBLabel(author).apply {
                    font = font.deriveFont(JBUI.scaleFontSize(12f))
                    foreground = JBColor.foreground()
                }

            val textPanel =
                JPanel().apply {
                    layout = BoxLayout(this, BoxLayout.Y_AXIS)
                    isOpaque = false
                    add(nameLabel)
                    add(Box.createVerticalStrut(JBUI.scale(4)))
                    add(versionLabel)
                    add(Box.createVerticalStrut(JBUI.scale(4)))
                    add(authorLabel)
                }

            add(textPanel, BorderLayout.CENTER)
        }

    private fun createSectionPanel(title: String, titleSpacing: Int = 4): JPanel =
        JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT

            if (title.isNotEmpty()) {
                val titleLabel =
                    JBLabel(title).apply {
                        font = font.deriveFont(Font.BOLD).deriveFont(JBUI.scaleFontSize(13f))
                        foreground = JBColor.foreground()
                        alignmentX = Component.LEFT_ALIGNMENT
                    }
                add(titleLabel)
                add(Box.createVerticalStrut(JBUI.scale(titleSpacing)))
            }
        }

    private fun createAcknowledgmentsSection(): JPanel =
        createSectionPanel("Acknowledgments").apply {
            val editorPane =
                JEditorPane().apply {
                    isEditable = false
                    isOpaque = false
                    putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)

                    val kit = HTMLEditorKit()
                    val styleSheet = StyleSheet()
                    val fgColor = colorToHex(JBColor.foreground())
                    val linkColor = "#589DF6"

                    styleSheet.addRule(
                        "body { font-family: Dialog; font-size: ${JBUI.scaleFontSize(12f)}px; color: $fgColor; margin: 0; padding: 0; }",
                    )
                    styleSheet.addRule("a { color: $linkColor; }")
                    styleSheet.addRule("b { font-weight: bold; }")
                    styleSheet.addRule("u { text-decoration: underline; }")

                    kit.styleSheet = styleSheet
                    editorKit = kit

                    text =
                        """
                        <html><body>
                        Built on the <a href="https://github.com/antlr/intellij-plugin-v4">ANTLR v4 IntelliJ Plugin</a> by Terence Parr,<br>
                        Sam Harwell, and contributors.<br><br>
                        <b><u>ANTLR4+ is independent and not affiliated with ANTLR.</u></b>
                        </body></html>
                        """
                            .trimIndent()

                    addHyperlinkListener { e ->
                        if (e.eventType == HyperlinkEvent.EventType.ACTIVATED) {
                            BrowserUtil.browse(e.url)
                        }
                    }

                    alignmentX = Component.LEFT_ALIGNMENT
                }
            add(editorPane)
        }

    private fun createSection(title: String, content: String): JPanel =
        createSectionPanel(title).apply {
            val contentLabel =
                JBLabel(
                        "<html><body style='width: ${JBUI.scale(440)}px'>${content.replace("\n", "<br>")}</body></html>",
                    )
                    .apply {
                        foreground = JBColor.foreground()
                        alignmentX = Component.LEFT_ALIGNMENT
                    }

            add(contentLabel)
        }

    private fun createLicenseSection(): JPanel =
        createSectionPanel("License").apply {
            val descLabel =
                JBLabel("Apache License 2.0 • No telemetry or personal data collection.").apply {
                    foreground = JBColor.foreground()
                    alignmentX = Component.LEFT_ALIGNMENT
                }
            add(descLabel)
            add(Box.createVerticalStrut(JBUI.scale(6)))

            val linksPanel =
                JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                    isOpaque = false
                    alignmentX = Component.LEFT_ALIGNMENT
                    add(
                        createLinkButton(
                            "View Apache 2.0 License",
                            "https://www.apache.org/licenses/LICENSE-2.0",
                        ),
                    )
                }
            add(linksPanel)
        }

    private fun createLinksSection(): JPanel =
        createSectionPanel("Resources", titleSpacing = 6).apply {
            val linksPanel =
                JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                    isOpaque = false
                    alignmentX = Component.LEFT_ALIGNMENT

                    add(createLinkButton("ANTLR Website", "https://www.antlr.org"))
                    add(Box.createHorizontalStrut(JBUI.scale(15)))
                    add(
                        createLinkButton(
                            "ANTLR Documentation",
                            "https://github.com/antlr/antlr4/blob/master/doc/index.md",
                        ),
                    )
                    add(Box.createHorizontalStrut(JBUI.scale(15)))
                    add(
                        createLinkButton(
                            "Report Issue",
                            "https://plugins.jetbrains.com/plugin/29781-antlr4-plus/reviews",
                        ),
                    )
                }

            add(linksPanel)
        }

    private fun createLinkButton(text: String, url: String): JButton =
        JButton(text).apply {
            isBorderPainted = false
            isContentAreaFilled = false
            foreground = JBColor(Color(0x589DF6), Color(0x589DF6))
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            font = font.deriveFont(JBUI.scaleFontSize(12f))
            border = JBUI.Borders.empty()

            addActionListener { BrowserUtil.browse(url) }
        }

    private fun colorToHex(color: Color): String = String.format("#%02x%02x%02x", color.red, color.green, color.blue)

    companion object {
        fun show(project: Project?) {
            AboutDialog(project).show()
        }
    }
}
