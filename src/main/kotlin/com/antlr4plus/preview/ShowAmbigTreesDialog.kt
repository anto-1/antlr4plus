package com.antlr4plus.preview

import com.antlr4plus.parsing.ParsingUtils
import com.antlr4plus.preview.tree.UberTreeViewer
import com.antlr4plus.state.PreviewState
import com.antlr4plus.util.rethrowFatalOrCancellation
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.ListPopup
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Font
import javax.swing.*
import org.antlr.v4.runtime.ParserRuleContext
import org.antlr.v4.runtime.RuleContext
import org.antlr.v4.runtime.atn.AmbiguityInfo
import org.antlr.v4.runtime.atn.LookaheadEventInfo
import org.antlr.v4.runtime.misc.Interval
import org.antlr.v4.runtime.misc.ParseCancellationException
import org.antlr.v4.runtime.tree.Tree
import org.antlr.v4.tool.Grammar
import org.antlr.v4.tool.LexerGrammar

class ShowAmbigTreesDialog : JDialog() {
    private val viewerDisposable = Disposer.newCheckedDisposable("ANTLR4+ ambiguity tree viewers")
    private val buttonOK = JButton("OK")
    private val treeScrollPane = JScrollPane()
    private val treeSizeSlider = JSlider(-400, 1000, 0)
    private val ambigPhraseLabel =
        JLabel("ambiguity").apply {
            font = font.deriveFont(Font.BOLD)
        }
    private val footer =
        JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(Box.createHorizontalGlue())
            add(ambigPhraseLabel)
            add(Box.createHorizontalGlue())
            add(buttonOK)
        }
    private val dialogContent =
        JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(10)
            add(treeScrollPane, BorderLayout.CENTER)
            add(
                JPanel(BorderLayout()).apply {
                    add(treeSizeSlider, BorderLayout.NORTH)
                    add(footer, BorderLayout.SOUTH)
                },
                BorderLayout.SOUTH,
            )
        }
    private var uberViewers: Array<UberTreeViewer> = emptyArray()

    init {
        setContentPane(dialogContent)
        isModal = false
        rootPane.defaultButton = buttonOK

        buttonOK.addActionListener { dispose() }

        treeSizeSlider.addChangeListener {
            setScale(treeSizeSlider.value / 1000.0 + 1.0)
        }
    }

    fun setScale(scale: Double) {
        uberViewers.forEach { uber ->
            uber.treeViewer.scale = scale
        }
        treeScrollPane.revalidate()
    }

    fun setTrees(
        previewState: PreviewState,
        grammar: Grammar,
        ambiguousParseTrees: List<RuleContext>,
        title: String,
        highlightTreeIndex: Int,
        highlightDiffs: Boolean,
        decisionRoots: List<ParserRuleContext?>? = null,
    ) {
        val numTrees = ambiguousParseTrees.size
        setTitle(title)

        val chosenTree = ambiguousParseTrees.getOrNull(highlightTreeIndex)

        uberViewers =
            Array(numTrees) { i ->
                val ctx = ambiguousParseTrees[i]
                val project = previewState.getProject()

                val (uber, _) = UberTreeViewer.createUberPanel(project, viewerDisposable)

                val treeText = AltLabelTextProvider(previewState.parsingResult!!.parser!!, grammar)
                uber.treeModel.treeTextProvider = treeText

                uber.treeViewer.setTree(ctx)

                val decisionRoot = decisionRoots?.getOrNull(i)
                if (decisionRoot != null) {
                    uber.treeViewer.highlightedNodes?.put(decisionRoot, true)
                }

                if (highlightDiffs && chosenTree != null && ctx !== chosenTree && i != highlightTreeIndex) {
                    val differingNodes = mutableSetOf<Tree>()
                    findDifferingNodes(chosenTree, ctx, differingNodes)
                    uber.treeViewer.differingNodes = differingNodes
                }

                uber
            }

        val panelOfTrees = JBPanel<JBPanel<*>>()
        panelOfTrees.layout = BoxLayout(panelOfTrees, BoxLayout.X_AXIS)

        uberViewers.forEachIndexed { i, uber ->
            if (i > 0) {
                panelOfTrees.add(JSeparator(JSeparator.VERTICAL))
            }

            val wrapper = JBPanel<JBPanel<*>>(BorderLayout())
            wrapper.background =
                if (i == highlightTreeIndex) JBColor(Color.WHITE, Color(60, 63, 65))
                else JBColor(JBColor.LIGHT_GRAY, Color(43, 43, 43))

            val actualTreePanel =
                JPanel(BorderLayout(0, 0)).apply {
                    add(uber.scrollpane, BorderLayout.CENTER)
                    add(uber.scaleslider, BorderLayout.SOUTH)
                }
            wrapper.add(actualTreePanel, BorderLayout.CENTER)
            panelOfTrees.add(wrapper)
        }

        treeScrollPane.setViewportView(panelOfTrees)
    }

    override fun dispose() {
        if (!viewerDisposable.isDisposed) Disposer.dispose(viewerDisposable)
        super.dispose()
    }

    companion object {
        private const val MAX_PHRASE_WIDTH = 25

        data class AmbiguityTreesResult(
            val trees: List<ParserRuleContext>,
            val decisionRoots: List<ParserRuleContext?>,
            // ATN decision alternatives, which may differ from outer rule alternatives after left recursion.
            val alternativeNumbers: List<Int>,
        )

        private fun loadGrammarsOnDemand(
            previewState: PreviewState,
        ): Pair<LexerGrammar?, Grammar?>? =
            previewState.grammarFile?.let { grammarFile ->
                try {
                    ParsingUtils.loadGrammars(previewState.getProject(), grammarFile)
                } catch (e: Exception) {
                    rethrowFatalOrCancellation(e)
                    null
                }
            }

        @JvmStatic
        fun createAmbigTreesPopup(previewState: PreviewState, ambigInfo: AmbiguityInfo): ListPopup =
            JBPopupFactory.getInstance()
                .createListPopup(
                    object :
                        BaseListPopupStep<String>(
                            "Choose Action",
                            listOf("Show all phrase interpretations"),
                        ) {
                        override fun onChosen(
                            selectedValue: String?,
                            finalChoice: Boolean,
                        ): PopupStep<*>? {
                            if (selectedValue != null) {
                                popupAmbigTreesDialog(previewState, ambigInfo)
                            }
                            return null
                        }
                    },
                )

        @JvmStatic
        fun popupAmbigTreesDialog(previewState: PreviewState, ambigInfo: AmbiguityInfo) {
            val dialog by lazy { ShowAmbigTreesDialog() }
            val originalParser =
                previewState.parsingResult?.parser
                    ?: run {
                        showError(
                            dialog,
                            "No parser available for ambiguity visualization.",
                            "Parser Not Available",
                        )
                        return
                    }

            var grammar = previewState.grammar
            var lexerGrammar = previewState.lexerGrammar

            if (grammar == null || lexerGrammar == null) {
                loadGrammarsOnDemand(previewState)?.let { (lexer, parser) ->
                    if (parser != null) grammar = parser
                    if (lexer != null) lexerGrammar = lexer
                }
            }

            if (grammar == null) {
                showError(
                    dialog,
                    "Could not load grammar for ambiguity visualization.<br>Please ensure the grammar file is available.",
                    "Grammar Not Available",
                )
                return
            }

            if (lexerGrammar == null) {
                showError(
                    dialog,
                    "Could not load lexer grammar for ambiguity visualization.<br>Please ensure the grammar file is available.",
                    "Lexer Grammar Not Available",
                )
                return
            }

            val originalTokens = originalParser.tokenStream
            val tokensToUse =
                createRemappedTokenStream(
                    originalTokens,
                    originalParser.vocabulary,
                    lexerGrammar!!,
                )

            // Use the grammar rule index to match the interpreter ATN.
            val startRuleName = previewState.startRuleName
            val startRuleIndex = grammar!!.getRule(startRuleName)?.index ?: originalParser.getRuleIndex(startRuleName)

            val maxDecision = grammar!!.atn.numberOfDecisions
            if (ambigInfo.decision < 0 || ambigInfo.decision >= maxDecision) {
                showError(
                    dialog,
                    "Decision index ${ambigInfo.decision} is out of range.<br>Grammar has $maxDecision decisions.<br>This may indicate grammar version mismatch.",
                    "Decision Mismatch",
                )
                return
            }

            val ambiguityResult: AmbiguityTreesResult? =
                try {
                    getAmbiguousParseTrees(
                        grammar!!,
                        tokensToUse,
                        ambigInfo.decision,
                        ambigInfo.ambigAlts,
                        ambigInfo.startIndex,
                        ambigInfo.stopIndex,
                        startRuleIndex,
                    )
                } catch (pce: ParseCancellationException) {
                    showError(
                        dialog,
                        "Cannot display ambiguous trees while there are syntax errors in your input.",
                        "Syntax Error",
                    )
                    null
                } catch (e: Exception) {
                    rethrowFatalOrCancellation(e)
                    val message = e.message ?: "Unknown error"
                    showError(
                        dialog,
                        "Error generating ambiguous trees: ${e.javaClass.simpleName}<br>$message<br>Decision: ${ambigInfo.decision}, Tokens: ${ambigInfo.startIndex}-${ambigInfo.stopIndex}",
                        "Error",
                    )
                    null
                }

            if (ambiguityResult != null) {
                val ambiguousParseTrees = ambiguityResult.trees
                var phrase = tokensToUse.getText(Interval.of(ambigInfo.startIndex, ambigInfo.stopIndex))
                if (phrase.length > MAX_PHRASE_WIDTH) {
                    phrase = phrase.substring(0, MAX_PHRASE_WIDTH) + "..."
                }
                val title = "${ambiguousParseTrees.size} Interpretations of Ambiguous Input Phrase: $phrase"
                dialog.ambigPhraseLabel.text = title
                dialog.setTrees(
                    previewState,
                    grammar!!,
                    ambiguousParseTrees,
                    title,
                    0,
                    true,
                    ambiguityResult.decisionRoots,
                )
            }

            dialog.pack()
            dialog.isVisible = true
        }

        @JvmStatic
        fun createLookaheadTreesPopup(
            previewState: PreviewState,
            lookaheadInfo: LookaheadEventInfo,
        ): ListPopup =
            JBPopupFactory.getInstance()
                .createListPopup(
                    object :
                        BaseListPopupStep<String>(
                            "Choose Action",
                            listOf("Show all lookahead interpretations"),
                        ) {
                        override fun onChosen(
                            selectedValue: String?,
                            finalChoice: Boolean,
                        ): PopupStep<*>? {
                            if (selectedValue != null) {
                                popupLookaheadTreesDialog(previewState, lookaheadInfo)
                            }
                            return null
                        }
                    },
                )

        @JvmStatic
        fun createCombinedPopup(
            previewState: PreviewState,
            ambigInfo: AmbiguityInfo,
            lookaheadInfo: LookaheadEventInfo,
        ): ListPopup =
            JBPopupFactory.getInstance()
                .createListPopup(
                    object :
                        BaseListPopupStep<String>(
                            "Choose Action",
                            listOf(
                                "Show ambiguity interpretations",
                                "Show lookahead interpretations",
                            ),
                        ) {
                        override fun onChosen(
                            selectedValue: String?,
                            finalChoice: Boolean,
                        ): PopupStep<*>? {
                            when (selectedValue) {
                                "Show ambiguity interpretations" -> popupAmbigTreesDialog(previewState, ambigInfo)
                                "Show lookahead interpretations" ->
                                    popupLookaheadTreesDialog(previewState, lookaheadInfo)
                            }
                            return null
                        }
                    },
                )

        @JvmStatic
        fun popupLookaheadTreesDialog(
            previewState: PreviewState,
            lookaheadInfo: LookaheadEventInfo,
        ) {
            val dialog by lazy { ShowAmbigTreesDialog() }
            val originalParser =
                previewState.parsingResult?.parser
                    ?: run {
                        showError(
                            dialog,
                            "No parser available for lookahead visualization.",
                            "Parser Not Available",
                        )
                        return
                    }

            var grammar = previewState.grammar
            var lexerGrammar = previewState.lexerGrammar

            if (grammar == null || lexerGrammar == null) {
                loadGrammarsOnDemand(previewState)?.let { (lexer, parser) ->
                    if (parser != null) grammar = parser
                    if (lexer != null) lexerGrammar = lexer
                }
            }

            if (grammar == null) {
                showError(
                    dialog,
                    "Could not load grammar for lookahead visualization.<br>Please ensure the grammar file is available.",
                    "Grammar Not Available",
                )
                return
            }

            if (lexerGrammar == null) {
                showError(
                    dialog,
                    "Could not load lexer grammar for lookahead visualization.<br>Please ensure the grammar file is available.",
                    "Lexer Grammar Not Available",
                )
                return
            }

            val originalTokens = originalParser.tokenStream
            val tokensToUse =
                createRemappedTokenStream(
                    originalTokens,
                    originalParser.vocabulary,
                    lexerGrammar!!,
                )

            // Use the grammar rule index to match the interpreter ATN.
            val startRuleName = previewState.startRuleName
            val startRuleIndex = grammar!!.getRule(startRuleName)?.index ?: originalParser.getRuleIndex(startRuleName)

            val maxDecision = grammar!!.atn.numberOfDecisions
            if (lookaheadInfo.decision < 0 || lookaheadInfo.decision >= maxDecision) {
                showError(
                    dialog,
                    "Decision index ${lookaheadInfo.decision} is out of range.<br>Grammar has $maxDecision decisions.<br>This may indicate grammar version mismatch.",
                    "Decision Mismatch",
                )
                return
            }

            val lookaheadResult: AmbiguityTreesResult? =
                try {
                    getLookaheadParseTrees(
                        grammar!!,
                        tokensToUse,
                        lookaheadInfo.decision,
                        lookaheadInfo,
                        lookaheadInfo.startIndex,
                        lookaheadInfo.stopIndex,
                        startRuleIndex,
                    )
                } catch (pce: ParseCancellationException) {
                    showError(
                        dialog,
                        "Cannot display lookahead trees while there are syntax errors in your input.",
                        "Syntax Error",
                    )
                    null
                } catch (e: Exception) {
                    rethrowFatalOrCancellation(e)
                    val message = e.message ?: "Unknown error"
                    showError(
                        dialog,
                        "Error generating lookahead trees: ${e.javaClass.simpleName}<br>$message<br>Decision: ${lookaheadInfo.decision}, Tokens: ${lookaheadInfo.startIndex}-${lookaheadInfo.stopIndex}",
                        "Error",
                    )
                    null
                }

            if (lookaheadResult != null) {
                val lookaheadParseTrees = lookaheadResult.trees
                if (lookaheadParseTrees.isEmpty()) {
                    showError(
                        dialog,
                        "No valid parse trees could be generated for this lookahead decision.",
                        "No Trees Generated",
                    )
                    dialog.pack()
                    dialog.isVisible = true
                    return
                }
                var phrase =
                    tokensToUse.getText(
                        Interval.of(lookaheadInfo.startIndex, lookaheadInfo.stopIndex),
                    )
                if (phrase.length > MAX_PHRASE_WIDTH) {
                    phrase = phrase.substring(0, MAX_PHRASE_WIDTH) + "..."
                }
                val predictedIndex = lookaheadResult.alternativeNumbers.indexOf(lookaheadInfo.predictedAlt)
                val title = "${lookaheadParseTrees.size} Interpretations of Lookahead Phrase: $phrase"
                dialog.ambigPhraseLabel.text = title
                dialog.setTrees(
                    previewState,
                    grammar!!,
                    lookaheadParseTrees,
                    title,
                    predictedIndex,
                    true,
                    lookaheadResult.decisionRoots,
                )
            }

            dialog.pack()
            dialog.isVisible = true
        }

        private fun showError(dialog: ShowAmbigTreesDialog, message: String, label: String) {
            val errPanel = JBPanel<JBPanel<*>>(BorderLayout())
            errPanel.add(JBLabel("<html>$message</html>"))
            dialog.treeScrollPane.setViewportView(errPanel)
            dialog.ambigPhraseLabel.text = label
            dialog.pack()
            dialog.isVisible = true
        }
    }
}
