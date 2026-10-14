package com.antlr4plus.actions

import com.antlr4plus.actions.ExtractRuleHandler.findExtractableRules
import com.antlr4plus.actions.MyActionUtils.getGrammarFileFromEvent
import com.antlr4plus.actions.MyActionUtils.getLexerRuleSurroundingRef
import com.antlr4plus.actions.MyActionUtils.getParserRuleSurroundingRef
import com.antlr4plus.actions.MyActionUtils.getSelectedPsiElement
import com.antlr4plus.dotG4Support.psi.RuleSpecNode
import com.antlr4plus.interaction.InteractionHandler
import com.antlr4plus.interaction.UserInteraction
import com.intellij.openapi.actionSystem.*
import com.intellij.psi.util.PsiTreeUtil

/** Extracts selected grammar text into a named rule. */
class ExtractRuleAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val interaction = UserInteraction.GrammarEditorAction.ExtractRule(e)
        InteractionHandler.process(interaction)
    }

    override fun update(e: AnActionEvent) {
        val presentation = e.presentation

        val grammarFile = getGrammarFileFromEvent(e)
        if (grammarFile == null) {
            presentation.isEnabled = false
            return
        }

        val editor = e.getData(PlatformDataKeys.EDITOR)
        if (editor == null) {
            presentation.isEnabled = false
            return
        }

        val parserRule = getParserRuleSurroundingRef(e)
        val lexerRule = getLexerRuleSurroundingRef(e)
        if (parserRule == null && lexerRule == null) {
            presentation.isEnabled = false
            return
        }

        val selectionModel = editor.selectionModel
        if (selectionModel.hasSelection()) {
            val file = e.getData(LangDataKeys.PSI_FILE)
            val text = editor.document.charsSequence
            var start = selectionModel.selectionStart
            var end = selectionModel.selectionEnd
            while (start < end && text[start].isWhitespace()) start++
            while (end > start && text[end - 1].isWhitespace()) end--
            val firstRule =
                file?.findElementAt(start)?.let {
                    PsiTreeUtil.getParentOfType(it, RuleSpecNode::class.java, false)
                }
            val lastRule =
                if (end > start)
                    file?.findElementAt(end - 1)?.let {
                        PsiTreeUtil.getParentOfType(it, RuleSpecNode::class.java, false)
                    }
                else null
            presentation.isEnabled = firstRule != null && firstRule === lastRule
            return
        } else {
            val el = getSelectedPsiElement(e)
            if (el == null || findExtractableRules(el).isEmpty()) {
                presentation.isEnabled = false
                return
            }
        }

        presentation.isEnabled = true
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
