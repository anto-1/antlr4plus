package com.antlr4plus.listener

import com.antlr4plus.actions.MyActionUtils
import com.antlr4plus.dotG4Support.highlighting.Highlighter
import com.antlr4plus.dotG4Support.psi.GrammarSpecNode
import com.antlr4plus.dotG4Support.psi.MyPsiUtils
import com.antlr4plus.editor.EditorUtils
import com.antlr4plus.editor.buildDecisionEventHint
import com.antlr4plus.editor.buildParseRegionHintText
import com.antlr4plus.editor.buildTokenInfoText
import com.antlr4plus.preview.ShowAmbigTreesDialog
import com.antlr4plus.previewService.PreviewService
import com.antlr4plus.state.PreviewState
import com.intellij.codeInsight.hint.HintManager
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseEventArea
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.editor.event.EditorMouseMotionListener
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.ui.JBColor
import java.awt.event.MouseEvent
import org.antlr.v4.runtime.Token
import org.antlr.v4.runtime.atn.AmbiguityInfo
import org.antlr.v4.runtime.atn.LookaheadEventInfo
import org.antlr.v4.runtime.tree.ParseTree
import org.antlr.v4.runtime.tree.RuleNode

/** Synchronizes viewer selection and provides token hints and grammar navigation in .g4p editors. */
class G4pEditorMouseListener(private val project: Project) : EditorMouseListener, EditorMouseMotionListener {

    override fun mouseExited(e: EditorMouseEvent) {
        Highlighter.clearTokenInfoHighlighters(e.editor)
    }

    override fun mouseClicked(e: EditorMouseEvent) {
        val mouseEvent = e.mouseEvent
        val offset = getEditorCharOffsetAndRemoveTokenHighlighters(e)
        if (offset < 0) return

        val editor = e.editor
        val previewService = project.service<PreviewService>()
        val previewState = previewService.state

        // Compare paths because editor and preview may hold different VirtualFile instances.
        val currentFile = FileDocumentManager.getInstance().getFile(editor.document)
        val g4pFile = previewState.g4pInputFile
        if (currentFile == null || g4pFile == null || currentFile.path != g4pFile.path) {
            return
        }

        if (mouseEvent.button == MouseEvent.BUTTON3) {
            handleRightClick(previewState, editor, offset)
            return
        }

        previewService.tokenTimelinePanel?.selectTokenAtOffset(offset)

        previewService.hierarchy?.let { hierarchy ->
            if (hierarchy.cursorFollowEnabled) {
                hierarchy.selectNodeAtOffset(offset)
            }
        }

        previewService.uber?.let { uber ->
            if (uber.cursorFollowEnabled) {
                uber.selectNodeAtOffset(offset)
            }
        }

        if (!mouseEvent.isControlDown && !mouseEvent.isAltDown) return

        if (previewState.parsingResult != null) {
            if (mouseEvent.isControlDown) {
                navigateToGrammarRule(previewState, offset)
            } else if (mouseEvent.isAltDown) {
                navigateToParserRule(previewState, offset)
            }
        }
    }

    override fun mouseMoved(e: EditorMouseEvent) {
        val offset = getEditorCharOffsetAndRemoveTokenHighlighters(e)
        if (offset < 0) return

        val editor = e.editor
        val previewService = project.service<PreviewService>()
        val previewState = previewService.state

        val currentFile = FileDocumentManager.getInstance().getFile(editor.document)
        val g4pFile = previewState.g4pInputFile
        if (currentFile == null || g4pFile == null || currentFile.path != g4pFile.path) {
            return
        }

        val mouseEvent = e.mouseEvent
        Highlighter.clearTokenInfoHighlighters(editor)

        if (mouseEvent.isControlDown && previewState.parsingResult != null) {
            showTokenInfoUponCtrlKey(editor, previewState, offset)
        } else if (mouseEvent.isAltDown && previewState.parsingResult != null) {
            showParseRegionUponAltKey(editor, previewState, offset)
        } else {
            showDecisionEventTooltip(editor, offset)
        }
    }

    private fun getEditorCharOffsetAndRemoveTokenHighlighters(e: EditorMouseEvent): Int {
        if (e.area != EditorMouseEventArea.EDITING_AREA) return -1

        val mouseEvent = e.mouseEvent
        val editor = e.editor
        val offset = MyActionUtils.getMouseOffset(mouseEvent, editor)

        if (offset >= editor.document.textLength) {
            return -1
        }

        Highlighter.clearTokenInfoHighlighters(editor)
        return offset
    }

    private fun showTokenInfoUponCtrlKey(
        editor: Editor,
        previewState: PreviewState,
        offset: Int,
    ) {
        val parsingResult = previewState.parsingResult ?: return

        val terminal = parsingResult.findTerminalAtOffset(offset) ?: return
        val tokenInfo = parsingResult.getTokenFromTerminal(terminal) ?: return

        val hintText = buildTokenInfoText(parsingResult, tokenInfo)

        // Box effects remain visible on whitespace.
        val docLength = editor.document.textLength
        val highlightStart = tokenInfo.startIndex.coerceIn(0, docLength)
        val highlightEnd = (tokenInfo.stopIndex + 1).coerceIn(highlightStart, docLength)
        if (highlightStart < highlightEnd) {
            val attr = TextAttributes()
            attr.effectColor = JBColor.BLUE
            attr.effectType = EffectType.BOXED
            editor.markupModel
                .addRangeHighlighter(
                    highlightStart,
                    highlightEnd,
                    HighlighterLayer.SELECTION,
                    attr,
                    HighlighterTargetArea.EXACT_RANGE,
                )
                .putUserData(Highlighter.TOKEN_INFO_HIGHLIGHT_KEY, true)
        }

        // HintManager anchors the HTML hint at the caret.
        editor.caretModel.moveToOffset(offset)
        HintManager.getInstance().showInformationHint(editor, hintText)
    }

    private fun showParseRegionUponAltKey(
        editor: Editor,
        previewState: PreviewState,
        offset: Int,
    ) {
        val parsingResult = previewState.parsingResult ?: return
        val ruleNames = parsingResult.ruleNames ?: return

        val terminal = parsingResult.findTerminalAtOffset(offset) ?: return
        val tokenInfo = parsingResult.getTokenFromTerminal(terminal) ?: return

        val rulePath = buildRulePathFromTree(terminal, ruleNames)
        if (rulePath.isEmpty()) return

        val hintText = buildParseRegionHintText(parsingResult, rulePath, tokenInfo.type)

        val innermostRule = getInnermostParserRuleFromTree(terminal)
        if (innermostRule != null) {
            // sourceInterval contains token indices; highlighting needs character offsets.
            val charRange = parsingResult.getRuleCharacterRange(innermostRule)
            if (charRange != null) {
                val docLength = editor.document.textLength
                val highlightStart = charRange.first.coerceIn(0, docLength)
                val highlightEnd = (charRange.second + 1).coerceIn(highlightStart, docLength)
                if (highlightStart < highlightEnd) {
                    // Box effects remain visible on whitespace.
                    val attr = TextAttributes()
                    attr.effectColor = JBColor.ORANGE
                    attr.effectType = EffectType.BOXED
                    editor.markupModel
                        .addRangeHighlighter(
                            highlightStart,
                            highlightEnd,
                            HighlighterLayer.SELECTION,
                            attr,
                            HighlighterTargetArea.EXACT_RANGE,
                        )
                        .putUserData(Highlighter.TOKEN_INFO_HIGHLIGHT_KEY, true)
                }
            }
        }

        // HintManager anchors the hint at the caret.
        editor.caretModel.moveToOffset(offset)
        HintManager.getInstance().showInformationHint(editor, hintText)
    }

    private fun buildRulePathFromTree(node: ParseTree, ruleNames: Array<String>): List<String> {
        val path = mutableListOf<String>()
        var current: ParseTree? = node.parent

        while (current != null) {
            if (current is RuleNode) {
                val ruleContext = current.ruleContext
                val ruleIndex = ruleContext?.ruleIndex ?: -1
                if (ruleIndex >= 0 && ruleIndex < ruleNames.size) {
                    path.add(0, ruleNames[ruleIndex])
                }
            }
            current = current.parent
        }

        return path
    }

    private fun getInnermostParserRuleFromTree(node: ParseTree): ParseTree? {
        var current: ParseTree? = node.parent
        while (current != null) {
            // KotlinTreeAdapter implements RuleNode even for terminals.
            if (current is com.antlr4plus.parsing.kotlin.KotlinTreeAdapter) {
                if (!current.isTerminalNode()) {
                    return current
                }
            } else if (current is RuleNode) {
                return current
            }
            current = current.parent
        }
        return null
    }

    private fun navigateToParserRule(
        previewState: PreviewState,
        offset: Int,
    ) {
        val parsingResult = previewState.parsingResult ?: return
        val ruleNames = parsingResult.ruleNames ?: return

        val terminal = parsingResult.findTerminalAtOffset(offset) ?: return

        val parserRuleNode = getInnermostParserRuleFromTree(terminal) ?: return
        val ruleIndex =
            if (parserRuleNode is RuleNode) {
                parserRuleNode.ruleContext?.ruleIndex ?: -1
            } else {
                -1
            }
        if (ruleIndex < 0 || ruleIndex >= ruleNames.size) return

        val ruleName = ruleNames[ruleIndex]

        val grammarFile = previewState.grammarFile ?: return

        navigateToRuleInFile(grammarFile, ruleName)
    }

    private fun navigateToGrammarRule(
        previewState: PreviewState,
        offset: Int,
    ) {
        val parsingResult = previewState.parsingResult ?: return

        val terminal = parsingResult.findTerminalAtOffset(offset) ?: return
        val tokenInfo = parsingResult.getTokenFromTerminal(terminal) ?: return

        val ruleName =
            if (tokenInfo.type == Token.EOF) {
                return
            } else if (tokenInfo.type >= 0) {
                parsingResult.getSymbolicName(tokenInfo.type) ?: return
            } else {
                return
            }

        val grammarFile = previewState.grammarFile
        val lexerGrammarFile = previewState.lexerGrammarFile

        val isLexerRule = ruleName.isNotEmpty() && ruleName[0].isUpperCase()
        val targetFile =
            if (isLexerRule && lexerGrammarFile != null) {
                lexerGrammarFile
            } else {
                grammarFile
            }

        if (targetFile != null) {
            navigateToRuleInFile(targetFile, ruleName)
        }
    }

    private fun navigateToRuleInFile(grammarFile: VirtualFile, ruleName: String) {
        val psiFile = PsiManager.getInstance(project).findFile(grammarFile) ?: return
        val grammarSpec = PsiTreeUtil.findChildOfType(psiFile, GrammarSpecNode::class.java)

        val ruleSpec = MyPsiUtils.findSpecNode(grammarSpec, ruleName)
        if (ruleSpec != null) {
            val editor = EditorUtils.getOrFocusEditor(project, grammarFile) ?: return
            val ruleOffset = ruleSpec.textOffset
            editor.caretModel.moveToOffset(ruleOffset)
            editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
            editor.selectionModel.setSelection(
                ruleSpec.textRange.startOffset,
                ruleSpec.textRange.endOffset,
            )
        }
    }

    private fun showDecisionEventTooltip(editor: Editor, offset: Int) {
        val highlightersAtOffset = MyActionUtils.getRangeHighlightersAtOffset(editor, offset)
        if (highlightersAtOffset.isEmpty()) return

        val ambigInfo =
            MyActionUtils.getHighlighterWithDecisionEventType(
                highlightersAtOffset,
                AmbiguityInfo::class.java,
            ) as? AmbiguityInfo

        val lookaheadInfo =
            MyActionUtils.getHighlighterWithDecisionEventType(
                highlightersAtOffset,
                LookaheadEventInfo::class.java,
            ) as? LookaheadEventInfo

        val hint = buildDecisionEventHint(ambigInfo, lookaheadInfo) ?: return

        editor.caretModel.moveToOffset(offset)
        HintManager.getInstance().showInformationHint(editor, hint.toString())
    }

    private fun handleRightClick(previewState: PreviewState, editor: Editor, offset: Int) {
        // Highlighters can outlive the parsing result; inspect them before requiring a result.
        val highlightersAtOffset = MyActionUtils.getRangeHighlightersAtOffset(editor, offset)
        if (highlightersAtOffset.isEmpty()) {
            return
        }

        val ambigInfo =
            MyActionUtils.getHighlighterWithDecisionEventType(
                highlightersAtOffset,
                AmbiguityInfo::class.java,
            ) as? AmbiguityInfo

        val lookaheadInfo =
            MyActionUtils.getHighlighterWithDecisionEventType(
                highlightersAtOffset,
                LookaheadEventInfo::class.java,
            ) as? LookaheadEventInfo

        if (ambigInfo == null && lookaheadInfo == null) {
            return
        }

        if (previewState.parsingResult == null) {
            HintManager.getInstance()
                .showErrorHint(
                    editor,
                    "Parse result not available. Please re-parse the input to view interpretations.",
                )
            return
        }

        when {
            ambigInfo != null && lookaheadInfo != null -> {
                val popup = ShowAmbigTreesDialog.createCombinedPopup(previewState, ambigInfo, lookaheadInfo)
                popup.showInBestPositionFor(editor)
            }
            ambigInfo != null -> {
                val popup = ShowAmbigTreesDialog.createAmbigTreesPopup(previewState, ambigInfo)
                popup.showInBestPositionFor(editor)
            }
            lookaheadInfo != null -> {
                val popup = ShowAmbigTreesDialog.createLookaheadTreesPopup(previewState, lookaheadInfo)
                popup.showInBestPositionFor(editor)
            }
        }
    }
}
