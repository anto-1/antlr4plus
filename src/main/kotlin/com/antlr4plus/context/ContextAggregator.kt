package com.antlr4plus.context

import com.antlr4plus.notifications.NotificationsUtils
import com.intellij.openapi.project.Project
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

enum class ContextSection(val displayName: String) {
    SUMMARY("Summary"),
    G4P_INPUT("G4P Input File"),
    GRAMMAR_FILES("Grammar Files"),
    EXTERNAL_JAVA("External Java Dependencies"),
    PROFILER("Profiler Data"),
    LEXER_TOKENS("Lexer Tokens"),
    HIERARCHY("Parse Tree Hierarchy"),
    PARSER_ERRORS("Parser Errors"),
    ANTLR_ERRORS("ANTLR Errors"),
    JAVAC_ERRORS("Java Compiler Errors"),
}

/** Exports selected preview and file sections as Markdown. */
class ContextAggregator(private val project: Project) {

    /** Cache section contents before selection, then copy the selected sections to the clipboard. */
    fun showDialogAndCopy() {
        // File-backed sections read saved content; this does not save pending editor changes.

        val contentMap = ContextContentCollector(project).collect()
        val availableSections = contentMap.keys

        if (availableSections.isEmpty()) {
            NotificationsUtils.showToast(project, "No context available to copy")
            return
        }

        val dialog = ContextExportDialog(project, availableSections, contentMap)
        if (dialog.showAndGet()) {
            val selectedSections = dialog.getSelectedSections()
            if (selectedSections.isEmpty()) {
                NotificationsUtils.showToast(project, "No sections selected")
                return
            }

            val markdown = contextMarkdown(selectedSections, contentMap)
            if (markdown.isNotEmpty()) {
                val selection = StringSelection(markdown)
                Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, null)
                NotificationsUtils.showToast(
                    project,
                    "Context copied to clipboard (${selectedSections.size} sections)",
                )
            } else {
                NotificationsUtils.showToast(project, "No context available to copy")
            }
        }
    }
}
