package com.antlr4plus.error

import androidx.compose.ui.awt.ComposePanel
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project

/** Displays stdout/stderr lines captured during compiled preview execution. */
class ComposeRuntimeConsolePanel(
    project: Project,
    parentDisposable: Disposable = project,
) {
    private val console =
        ComposeTextConsolePanel(
            project = project,
            parentDisposable = parentDisposable,
            emptyConsoleMessage =
                "Runtime console. Output from your lexer/parser actions (println, System.out, exceptions) will appear here while the preview runs.",
            markdownHeading = "## Runtime Output",
            copyConfirmation = "Runtime output copied to clipboard as Markdown",
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
        // Heuristically count error-like output for the unread badge.
        private val ERROR_KEYWORDS =
            listOf(
                "exception",
                "error:",
                " error ",
                "stack trace",
                "at ",
            )

        private fun isSuccessLine(line: String): Boolean {
            val lowerLine = line.lowercase()
            return ERROR_KEYWORDS.none { lowerLine.contains(it) }
        }
    }
}
