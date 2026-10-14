package com.antlr4plus.actions

import com.antlr4plus.dotG4Support.psi.ParserRuleRefNode
import com.antlr4plus.previewService.PreviewService
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.wm.ToolWindowManager
import java.awt.event.MouseEvent
import java.util.function.Supplier

class TestRuleAction : AnAction(), DumbAware {
    override fun update(e: AnActionEvent) {
        val presentation = e.presentation
        presentation.text = "Test ANTLR Rule"
        presentation.icon = AllIcons.Actions.Execute

        val grammarFile = MyActionUtils.getGrammarFileFromEvent(e)
        if (grammarFile == null) {
            presentation.isEnabled = false
            presentation.isVisible = false
            return
        }

        val project = e.project
        if (project != null) {
            val previewService = project.service<PreviewService>()
            val activeGrammarFile = previewService.state.grammarFile
            val activeLexerFile = previewService.state.lexerGrammarFile

            if (activeGrammarFile == null && activeLexerFile == null) {
                presentation.isEnabled = false
                presentation.isVisible = true
                presentation.text = "Test Rule (no active preview)"
                return
            }

            val isActiveGrammar =
                grammarFile.path == activeGrammarFile?.path || grammarFile.path == activeLexerFile?.path
            if (!isActiveGrammar) {
                presentation.isEnabled = false
                presentation.isVisible = true
                presentation.text = "Test Rule (not active grammar)"
                return
            }
        }

        val inputEvent = e.inputEvent
        val rule =
            if (inputEvent is MouseEvent) {
                MyActionUtils.getParserRuleSurroundingRef(e)
            } else {
                getParserRuleAtMousePosition(e) ?: MyActionUtils.getParserRuleSurroundingRef(e)
            }
        if (rule == null) {
            presentation.isEnabled = false
            return
        }

        presentation.isVisible = true
        val ruleName = rule.text
        if (Character.isLowerCase(ruleName[0])) {
            presentation.isEnabled = true
            presentation.text = "Test Rule $ruleName"
        } else {
            presentation.isEnabled = false
        }
    }

    private fun getParserRuleAtMousePosition(e: AnActionEvent): ParserRuleRefNode? {
        // The caret may not yet reflect the mouse position during action updates.
        val editor = e.getData(PlatformDataKeys.EDITOR) ?: return null
        val mousePosition = editor.contentComponent.getMousePosition() ?: return null
        val pos =
            e.updateSession.compute(
                this,
                "xyToLogicalPosition",
                ActionUpdateThread.EDT,
                Supplier { editor.xyToLogicalPosition(mousePosition) },
            )
        val offset = editor.logicalPositionToOffset(pos)
        val file = e.getData(LangDataKeys.PSI_FILE) ?: return null
        val el = file.findElementAt(offset) ?: return null
        return MyActionUtils.getParserRuleSurroundingRef(el)
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val grammarFile = MyActionUtils.getGrammarFileFromEvent(e) ?: return
        val ruleName = MyActionUtils.getParserRuleSurroundingRef(e)?.text ?: return

        FileDocumentManager.getInstance().getDocument(grammarFile)?.let {
            FileDocumentManager.getInstance().saveDocument(it)
        }

        ToolWindowManager.getInstance(project).getToolWindow("ANTLR4 Plus")?.show()

        val previewService = project.service<PreviewService>()
        previewService.setStartRuleAndReparse(ruleName)
    }

    companion object {
        val LOG: Logger = Logger.getInstance("ANTLR TestRuleAction")
    }
}
