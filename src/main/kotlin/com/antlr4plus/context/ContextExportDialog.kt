package com.antlr4plus.context

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBCheckBox
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.GridLayout
import javax.swing.BorderFactory
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

internal class ContextExportDialog(
    project: Project,
    private val availableSections: Set<ContextSection>,
    private val contentMap: Map<ContextSection, String>,
) : DialogWrapper(project) {

    private val checkboxes = mutableMapOf<ContextSection, JBCheckBox>()
    private val statusLabel = JLabel()

    init {
        title = "Export Context to Clipboard"
        setOKButtonText("Copy")
        init()
        updateStatus()
    }

    @OptIn(ExperimentalStdlibApi::class)
    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.border = JBUI.Borders.empty(10)

        val headerLabel = JLabel("Select sections to include:")
        headerLabel.border = JBUI.Borders.emptyBottom(10)
        panel.add(headerLabel, BorderLayout.NORTH)

        val checkboxPanel = JPanel(GridLayout(0, 1, 0, 4))
        checkboxPanel.border = BorderFactory.createEmptyBorder(0, 10, 0, 10)

        for (section in ContextSection.entries) {
            val isAvailable = section in availableSections
            val content = contentMap[section]
            val lines = content?.lines()?.size ?: 0
            val label =
                if (isAvailable) {
                    "${section.displayName} ($lines lines)"
                } else {
                    section.displayName
                }
            val checkbox = JBCheckBox(label)
            checkbox.isSelected = isAvailable
            checkbox.isEnabled = isAvailable
            if (!isAvailable) {
                checkbox.toolTipText = "No data available"
            }
            checkbox.addActionListener { updateStatus() }
            checkboxes[section] = checkbox
            checkboxPanel.add(checkbox)
        }

        panel.add(checkboxPanel, BorderLayout.CENTER)

        val bottomPanel = JPanel(BorderLayout())
        bottomPanel.border = JBUI.Borders.emptyTop(10)

        val buttonPanel = JPanel()
        val selectAllButton = javax.swing.JButton("Select All")
        selectAllButton.addActionListener {
            checkboxes.forEach { (section, checkbox) ->
                if (section in availableSections) checkbox.isSelected = true
            }
            updateStatus()
        }
        val deselectAllButton = javax.swing.JButton("Deselect All")
        deselectAllButton.addActionListener {
            checkboxes.forEach { (_, checkbox) -> checkbox.isSelected = false }
            updateStatus()
        }
        buttonPanel.add(selectAllButton)
        buttonPanel.add(deselectAllButton)
        bottomPanel.add(buttonPanel, BorderLayout.NORTH)

        statusLabel.border = JBUI.Borders.emptyTop(8)
        statusLabel.foreground = com.intellij.util.ui.UIUtil.getLabelInfoForeground()
        bottomPanel.add(statusLabel, BorderLayout.SOUTH)

        panel.add(bottomPanel, BorderLayout.SOUTH)

        return panel
    }

    private fun updateStatus() {
        val selected = getSelectedSections()
        if (selected.isEmpty()) {
            statusLabel.text = "Nothing selected"
            return
        }
        var totalLines = 0
        var totalChars = 0
        for (section in selected) {
            val content = contentMap[section] ?: continue
            totalLines += content.lines().size
            totalChars += content.length
        }
        val charDisplay =
            if (totalChars > 1_000_000) {
                "%.1f MB".format(totalChars / 1_000_000.0)
            } else if (totalChars > 1_000) {
                "%.1f KB".format(totalChars / 1_000.0)
            } else {
                "$totalChars chars"
            }
        statusLabel.text = "${selected.size} sections, ~$totalLines lines, $charDisplay"
    }

    fun getSelectedSections(): Set<ContextSection> = checkboxes.filter { it.value.isSelected }.keys
}
