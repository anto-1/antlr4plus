package com.antlr4plus.dotG4Support.formatting

import com.antlr4plus.dotG4Support.language.LanguageFileType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project

class FormatGrammarAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        val isG4File = file?.fileType == LanguageFileType || file?.extension?.lowercase() == "g4"
        e.presentation.isEnabledAndVisible = isG4File && e.getData(CommonDataKeys.EDITOR) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val document = editor.document

        formatDocument(project, document)
    }

    companion object {
        fun formatDocument(project: Project, document: Document) {
            val text = document.text
            if (text.isBlank()) return

            val formatter = ANTLRv4DirectFormatter(ANTLRv4FormattingSettings())
            val formattedText =
                try {
                    formatter.format(text)
                } catch (_: Exception) {
                    return
                }

            if (formattedText != text) {
                WriteCommandAction.runWriteCommandAction(project) {
                    document.setText(formattedText)
                }
            }
        }
    }
}
