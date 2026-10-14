package com.antlr4plus.actions

import com.antlr4plus.dotG4Support.psi.LexerRuleRefNode
import com.antlr4plus.dotG4Support.psi.LexerRuleSpecNode
import com.antlr4plus.dotG4Support.psi.ParserRuleRefNode
import com.antlr4plus.dotG4Support.psi.ParserRuleSpecNode
import com.antlr4plus.dotG4Support.psi.RuleSpecNode
import com.antlr4plus.statistics.ComposeProfilerPanel
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.LangDataKeys
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.util.Condition
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import java.awt.Point
import java.awt.event.MouseEvent
import org.antlr.v4.runtime.atn.DecisionEventInfo

object MyActionUtils {
    @JvmStatic
    fun getGrammarFileFromEvent(e: AnActionEvent): VirtualFile? {
        val file =
            e.getData(LangDataKeys.VIRTUAL_FILE_ARRAY)?.firstOrNull()
                ?: e.getData(LangDataKeys.VIRTUAL_FILE)
                ?: e.getData(LangDataKeys.PSI_FILE)?.virtualFile
        return file?.takeIf { it.extension == "g4" }
    }

    fun getMouseOffset(mouseEvent: MouseEvent, editor: Editor): Int {
        val point = Point(mouseEvent.getPoint())
        val pos = editor.xyToLogicalPosition(point)
        return editor.logicalPositionToOffset(pos)
    }

    @JvmStatic
    fun getRangeHighlightersAtOffset(editor: Editor, offset: Int): MutableList<RangeHighlighter?> {
        val markupModel = editor.markupModel
        val highlightersAtOffset: MutableList<RangeHighlighter?> = ArrayList()
        for (r in markupModel.allHighlighters) {
            val a = r.startOffset
            val b = r.endOffset
            if (offset in a until b) {
                highlightersAtOffset.add(r)
            }
        }
        return highlightersAtOffset
    }

    fun getHighlighterWithDecisionEventType(
        highlighters: MutableList<RangeHighlighter?>,
        decisionEventType: Class<*>?,
    ): DecisionEventInfo? {
        for (r in highlighters) {
            val eventInfo = r?.getUserData(ComposeProfilerPanel.HIGHLIGHT_G4P_INFO_KEY)
            if (eventInfo != null) {
                if (decisionEventType?.isInstance(eventInfo) == true) {
                    return eventInfo
                }
            }
        }
        return null
    }

    @JvmStatic
    fun getParserRuleSurroundingRef(e: AnActionEvent): ParserRuleRefNode? =
        getParserRuleSurroundingRef(getSelectedPsiElement(e))

    fun getParserRuleSurroundingRef(element: PsiElement?): ParserRuleRefNode? =
        getRuleSurroundingRef(element, ParserRuleSpecNode::class.java)?.let { ruleSpecNode ->
            PsiTreeUtil.findChildOfType(ruleSpecNode, ParserRuleRefNode::class.java)
        }

    @JvmStatic
    fun getLexerRuleSurroundingRef(e: AnActionEvent): LexerRuleRefNode? {
        val selectedPsiNode = getSelectedPsiElement(e)
        val ruleSpecNode = getRuleSurroundingRef(selectedPsiNode, LexerRuleSpecNode::class.java) ?: return null
        return PsiTreeUtil.findChildOfType(ruleSpecNode, LexerRuleRefNode::class.java)
    }

    fun getRuleSurroundingRef(
        selectedPsiNode: PsiElement?,
        ruleSpecNodeClass: Class<out RuleSpecNode?>?,
    ): RuleSpecNode? {
        var currentNode = selectedPsiNode ?: return null
        if (currentNode.javaClass != ruleSpecNodeClass) {
            currentNode =
                PsiTreeUtil.findFirstParent(
                    currentNode,
                    Condition { psiElement: PsiElement ->
                        psiElement.javaClass == ruleSpecNodeClass
                    },
                ) ?: return null
        }
        return currentNode as RuleSpecNode
    }

    @JvmStatic
    fun getSelectedPsiElement(e: AnActionEvent): PsiElement? {
        val editor = e.getData(PlatformDataKeys.EDITOR)

        if (editor == null) {
            val selectedNavElement = e.getData(LangDataKeys.PSI_ELEMENT)
            if (selectedNavElement == null || selectedNavElement !is ParserRuleRefNode) {
                return null
            }
            return selectedNavElement
        }

        val file = e.getData(LangDataKeys.PSI_FILE) ?: return null

        val offset = editor.caretModel.offset
        return file.findElementAt(offset)
    }

    @JvmStatic
    fun showOnlyIfSelectionIsRule(e: AnActionEvent, title: String) {
        val presentation = e.presentation
        presentation.isEnabled = false
        val grammarFile = getGrammarFileFromEvent(e)
        if (grammarFile == null) {
            presentation.isEnabled = false
            return
        }

        val el = getSelectedPsiElement(e)
        if (el == null) {
            presentation.isEnabled = false
            return
        }

        val parserRule = getParserRuleSurroundingRef(e)
        val lexerRule = getLexerRuleSurroundingRef(e)

        if ((lexerRule != null && el is LexerRuleRefNode) || (parserRule != null && el is ParserRuleRefNode)) {
            val ruleName: String = el.text
            presentation.text = String.format(title, ruleName)
            presentation.isEnabled = true
        } else {
            presentation.isEnabled = false
        }
    }
}
