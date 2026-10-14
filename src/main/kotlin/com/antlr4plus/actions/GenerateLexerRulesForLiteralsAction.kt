package com.antlr4plus.actions

import com.antlr4plus.actions.MyActionUtils.getGrammarFileFromEvent
import com.antlr4plus.dotG4Support.generators.LiteralChooser
import com.antlr4plus.dotG4Support.generators.LiteralLexerRules
import com.intellij.codeInsight.hint.HintManager
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.psi.PsiDocumentManager
import com.intellij.refactoring.actions.BaseRefactoringAction

class GenerateLexerRulesForLiteralsAction : AnAction() {
    override fun update(e: AnActionEvent) {
        val grammarFile = getGrammarFileFromEvent(e)
        val file = e.getData(LangDataKeys.PSI_FILE)
        val editor = e.getData(PlatformDataKeys.EDITOR)
        e.presentation.isEnabled =
            grammarFile != null &&
                file != null &&
                editor != null &&
                BaseRefactoringAction.getElementAtCaret(editor, file) != null
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        LOG.info("actionPerformed GenerateLexerRulesForLiteralsAction")
        val project = e.project ?: return
        val psiFile = e.getData(LangDataKeys.PSI_FILE) ?: return
        val editor = e.getData(PlatformDataKeys.EDITOR) ?: return
        val generation = LiteralLexerRules.from(editor.document.text) ?: return
        if (generation.rules.isEmpty()) return
        val caretOffset = editor.caretModel.offset
        val scrollOffset = editor.scrollingModel.verticalScrollOffset
        val chooser = LiteralChooser(project, generation.rules.toMutableList(), generation::additionalRules)
        chooser.show()
        val selected = chooser.getSelectedElements()?.filterNotNull() ?: return
        val plan = generation.plan(selected, caretOffset) ?: return
        var applied = false
        WriteCommandAction.runWriteCommandAction(
            project,
            "Generate Lexer Rules",
            null,
            Runnable {
                applied = plan.applyTo(editor.document)
                if (applied) PsiDocumentManager.getInstance(project).commitDocument(editor.document)
            },
            psiFile,
        )
        if (applied) {
            ApplicationManager.getApplication().invokeLater {
                if (!editor.isDisposed) editor.scrollingModel.scrollVertically(scrollOffset)
            }
        } else {
            HintManager.getInstance().showErrorHint(editor, "The grammar changed or contains read-only text.")
        }
    }

    companion object {
        val LOG: Logger = Logger.getInstance("GenerateLexerRulesForLiterals")
    }
}
