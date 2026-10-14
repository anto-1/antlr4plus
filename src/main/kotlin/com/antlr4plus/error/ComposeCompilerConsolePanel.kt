package com.antlr4plus.error

import androidx.compose.ui.awt.ComposePanel
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project

class ComposeCompilerConsolePanel(
    project: Project,
    parentDisposable: Disposable = project,
) {
    private val console =
        ComposeTextConsolePanel(
            project = project,
            parentDisposable = parentDisposable,
            emptyConsoleMessage = "Compiler console. Java/Kotlin compilation errors will appear here.",
            markdownHeading = "## Compiler Output",
            copyConfirmation = "Compiler errors copied to clipboard as Markdown",
            isSuccessLine = ::isSuccessLine,
        )

    fun getLineCount(): Int = console.getLineCount()

    fun appendLine(line: String): Unit = console.appendLine(line)

    fun appendMultiLine(message: String): Unit = console.appendMultiLine(message)

    fun clear(): Unit = console.clear()

    /** Snapshot of all retained console lines for export. */
    fun getErrorsForExport(): List<String>? = console.getErrorsForExport()

    fun getComponent(): ComposePanel = console.getComponent()

    companion object {
        // Informational output does not contribute to the error badge.
        private val SUCCESS_KEYWORDS =
            listOf(
                "compilation success",
                "compiled success",
                "build success",
                "no errors",
                "0 errors",
            )

        private fun isSuccessLine(line: String): Boolean {
            val lowerLine = line.lowercase()
            return SUCCESS_KEYWORDS.any { lowerLine.contains(it) }
        }
    }
}
