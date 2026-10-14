package com.antlr4plus.parsing

import com.antlr4plus.util.escapeHtml
import com.intellij.openapi.ui.DialogBuilder
import com.intellij.openapi.ui.DialogWrapper
import java.awt.BorderLayout
import java.awt.Dimension
import java.io.File
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JRadioButton
import javax.swing.JScrollPane

/** Shows choices on the EDT; the caller owns dialog serialization and resolution state. */
internal fun showDependencyConflictDialog(
    conflicts: Map<String, List<File>>,
    scopeFolder: File,
    grammarName: String,
    conflictCount: Int,
): Map<String, File>? {
    val selections = mutableMapOf<String, File>()
    conflicts.forEach { (className, files) ->
        val sortedFiles = files.sortedByRelativeDepth(scopeFolder)
        selections[className] = sortedFiles.first()
    }

    val mainPanel =
        JPanel(BorderLayout(0, 10)).apply {
            border = BorderFactory.createEmptyBorder(15, 15, 15, 15)
        }

    val headerPanel =
        JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)

            val headerText =
                "<html><div style='width: 380px;'>" +
                    "Found <b>$conflictCount</b> dependency conflict${if (conflictCount > 1) "s" else ""} " +
                    "for <b>${escapeHtml(grammarName)}.g4</b>" +
                    "</div></html>"
            add(JLabel(headerText))
        }
    mainPanel.add(headerPanel, BorderLayout.NORTH)

    val selectionPanel =
        JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createEmptyBorder(5, 5, 5, 5)
        }

    for ((className, files) in conflicts) {
        val sortedFiles = files.sortedByRelativeDepth(scopeFolder)

        val classLabel = JLabel("<html><b>${escapeHtml(className)}</b></html>")
        selectionPanel.add(classLabel)
        selectionPanel.add(Box.createVerticalStrut(3))

        val buttonGroup = ButtonGroup()

        sortedFiles.forEachIndexed { index, file ->
            val relativePath = relativePathOrAbsolute(file, scopeFolder)
            val radio =
                JRadioButton("<html><b>${escapeHtml(relativePath)}</b></html>").apply {
                    isSelected = index == 0
                    buttonGroup.add(this)
                }

            radio.addActionListener {
                if (radio.isSelected) {
                    selections[className] = file
                }
            }

            selectionPanel.add(radio)
        }
        selectionPanel.add(Box.createVerticalStrut(10))
    }

    val selectionScrollPane =
        JScrollPane(selectionPanel).apply {
            preferredSize = Dimension(400, 200)
            border = null
        }
    mainPanel.add(selectionScrollPane, BorderLayout.CENTER)

    val dialogBuilder =
        DialogBuilder().apply {
            setTitle("Resolve Dependency Conflicts")
            setCenterPanel(mainPanel)
            addOkAction()
            addCancelAction()
            window.preferredSize = Dimension(450, 300)
        }

    val dialogResult = dialogBuilder.show()

    return if (dialogResult == DialogWrapper.OK_EXIT_CODE) selections else null
}

private fun List<File>.sortedByRelativeDepth(scopeFolder: File): List<File> = sortedBy { file ->
    val relativePath = relativePathOrAbsolute(file, scopeFolder)
    relativePath.count { it == File.separatorChar }
}

private fun relativePathOrAbsolute(file: File, scopeFolder: File): String =
    try {
        scopeFolder.toPath().relativize(file.toPath()).toString()
    } catch (e: Exception) {
        file.absolutePath
    }
