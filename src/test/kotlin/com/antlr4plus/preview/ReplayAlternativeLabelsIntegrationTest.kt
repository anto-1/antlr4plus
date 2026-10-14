package com.antlr4plus.preview

import com.antlr4plus.parsing.ParsingResult
import com.antlr4plus.parsing.PreviewParser
import com.antlr4plus.preview.tree.UberTreeViewer
import com.antlr4plus.state.PreviewState
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.Window
import java.util.BitSet
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.ParserRuleContext
import org.antlr.v4.runtime.atn.ATNConfig
import org.antlr.v4.runtime.atn.ATNConfigSet
import org.antlr.v4.runtime.atn.AmbiguityInfo
import org.antlr.v4.runtime.atn.DecisionState
import org.antlr.v4.runtime.atn.EmptyPredictionContext
import org.antlr.v4.runtime.atn.LookaheadEventInfo
import org.antlr.v4.runtime.atn.PredictionMode
import org.antlr.v4.runtime.tree.TerminalNodeImpl
import org.antlr.v4.tool.Grammar
import org.antlr.v4.tool.GrammarInterpreterRuleContext

/** Checks the text actually supplied to replay viewers, including original left-recursive labels. */
class ReplayAlternativeLabelsIntegrationTest : BasePlatformTestCase() {
    fun testAmbiguityDialogShowsNamedAlternatives() = checkChoices(lookahead = false, labelled = true)

    fun testLookaheadDialogShowsNamedAlternatives() = checkChoices(lookahead = true, labelled = true)

    fun testAmbiguityDialogShowsOriginalLeftRecursiveLabels() = checkLeftRecursive(lookahead = false)

    fun testLookaheadDialogShowsOriginalLeftRecursiveLabels() = checkLeftRecursive(lookahead = true)

    fun testUnlabelledAlternativesKeepNumbersAndSingleRulesOmitNumbers() =
        checkChoices(lookahead = false, labelled = false)

    private fun checkChoices(lookahead: Boolean, labelled: Boolean) {
        val alternatives = if (labelled) "leftValue #First | rightValue #Second" else "leftValue | rightValue"
        val fixture =
            prepare(
                "grammar Choices; start: choice EOF; choice: $alternatives; leftValue: X; rightValue: X; X: 'x';",
                "x",
                "choice",
                2,
            )
        withReplayDialog(fixture, lookahead, listOf(1, 2), tokenIndex = 0) { viewers ->
            assertEquals(
                if (labelled) listOf("choice:First", "choice:Second") else listOf("choice:1", "choice:2"),
                rootLabels(viewers),
            )
            assertEquals(
                listOf("leftValue", "rightValue"),
                viewers.map { it.treeViewer.getText(it.treeViewer.rootTree!!.getChild(0)) },
            )
            viewers.forEach {
                assertEquals("X", it.treeViewer.getText(it.treeViewer.rootTree!!.getChild(0).getChild(0)))
            }
        }
    }

    private fun checkLeftRecursive(lookahead: Boolean) {
        val fixture =
            prepare(
                "grammar Expressions; start: expr EOF; expr: INT #Number | expr '*' expr #Multiply | expr '+' expr #AddOne | expr '+' expr #AddTwo; INT: [0-9]+;",
                "1+2",
                "expr",
                3,
            )
        withReplayDialog(fixture, lookahead, listOf(2, 3), tokenIndex = 1) { viewers ->
            assertEquals(
                listOf(3, 4),
                viewers.map { (it.treeViewer.rootTree as GrammarInterpreterRuleContext).outerAltNum },
            )
            assertEquals(listOf("expr:AddOne", "expr:AddTwo"), rootLabels(viewers))
            viewers.forEach {
                val root = it.treeViewer.rootTree!!
                assertEquals("expr:Number", it.treeViewer.getText(root.getChild(0)))
                assertEquals("expr:Number", it.treeViewer.getText(root.getChild(2)))
            }
        }
    }

    fun testSharedLabelNamesAreUsedForEveryMatchingAlternative() {
        val fixture =
            prepare(
                "grammar Shared; start: choice EOF; choice: 'x' #Value | 'x' #Value;",
                "x",
                "choice",
                2,
            )
        withReplayDialog(fixture, false, listOf(1, 2), tokenIndex = 0) { viewers ->
            assertEquals(listOf("choice:Value", "choice:Value"), rootLabels(viewers))
        }
    }

    fun testOriginalPreviewContextsKeepNamedAndNumericLabels() {
        for (labelled in listOf(false, true)) {
            val alternatives = if (labelled) "leftValue #First | rightValue #Second" else "leftValue | rightValue"
            val grammar =
                Grammar("grammar Preview; start: choice EOF; choice: $alternatives; leftValue: 'x'; rightValue: 'x';")
            val decision = grammar.atn.decisionToState.single { it.ruleIndex == grammar.getRule("choice").index }
            for (alt in 1..2) {
                val parser = PreviewParser(grammar, tokens(grammar, "x"))
                parser.addDecisionOverride(decision.decision, 0, alt)
                val root = parser.parse(grammar.getRule("start").index)
                assertEquals(0, parser.numberOfSyntaxErrors)
                val provider = AltLabelTextProvider(parser, grammar)
                assertEquals("start", provider.getText(root))
                val expected = if (labelled) listOf("First", "Second")[alt - 1] else alt.toString()
                assertEquals("choice:$expected", provider.getText(root.getChild(0)))
            }
        }
    }

    fun testTerminalLabelsRetainGrammarVocabularyAndEof() {
        val grammar = Grammar("grammar Tokens; start: ID PLUS IMPORT EOF; PLUS: '+'; IMPORT: 'IMPORT'; ID: [a-z]+;")
        val stream = tokens(grammar, "alpha+IMPORT")
        val original = Grammar("grammar Original; start: OTHER EOF; OTHER: [a-z]+;")
        val parser = original.createGrammarParserInterpreter(tokens(original, "alpha"))
        val provider = AltLabelTextProvider(parser, grammar)
        assertEquals(
            listOf("ID:alpha", "PLUS:+", "IMPORT", "<EOF>"),
            stream.tokens.map { provider.getText(TerminalNodeImpl(it)) },
        )
    }

    private data class Fixture(val state: PreviewState, val tokens: CommonTokenStream, val decision: DecisionState)

    private fun tokens(grammar: Grammar, input: String) =
        CommonTokenStream(grammar.implicitLexer.createLexerInterpreter(CharStreams.fromString(input))).apply { fill() }

    private fun prepare(source: String, input: String, rule: String, transitions: Int): Fixture {
        val grammar = Grammar(source)
        val tokens = tokens(grammar, input)
        val parser = grammar.createGrammarParserInterpreter(tokens)
        parser.setProfile(true)
        parser.interpreter.predictionMode = PredictionMode.LL_EXACT_AMBIG_DETECTION
        val tree = parser.parse(grammar.getRule("start").index)
        assertEquals(0, parser.numberOfSyntaxErrors)
        val state =
            PreviewState(project).apply {
                this.grammar = grammar
                lexerGrammar = grammar.implicitLexer
                startRuleName = "start"
                parsingResult = ParsingResult.java(parser, tree, null)
            }
        val decision =
            grammar.atn.decisionToState.single {
                it.ruleIndex == grammar.getRule(rule).index && it.numberOfTransitions == transitions
            }
        return Fixture(state, tokens, decision)
    }

    private fun withReplayDialog(
        fixture: Fixture,
        lookahead: Boolean,
        alternatives: List<Int>,
        tokenIndex: Int,
        check: (List<UberTreeViewer>) -> Unit,
    ) {
        val before = Window.getWindows().toSet()
        try {
            if (lookahead) {
                val configurations =
                    ATNConfigSet().apply {
                        alternatives.forEach { add(ATNConfig(fixture.decision, it, EmptyPredictionContext.Instance)) }
                    }
                ShowAmbigTreesDialog.popupLookaheadTreesDialog(
                    fixture.state,
                    LookaheadEventInfo(
                        fixture.decision.decision,
                        configurations,
                        alternatives.first(),
                        fixture.tokens,
                        tokenIndex,
                        tokenIndex,
                        false,
                    ),
                )
            } else {
                val alts = BitSet().apply { alternatives.forEach { set(it) } }
                ShowAmbigTreesDialog.popupAmbigTreesDialog(
                    fixture.state,
                    AmbiguityInfo(fixture.decision.decision, null, alts, fixture.tokens, tokenIndex, tokenIndex, true),
                )
            }
            val dialogs = Window.getWindows().filterIsInstance<ShowAmbigTreesDialog>().filter { it !in before }
            assertEquals("Expected one replay dialog", 1, dialogs.size)
            val dialog = dialogs.single()
            val viewers =
                (dialog.javaClass.getDeclaredField("uberViewers").apply { isAccessible = true }.get(dialog) as Array<*>)
                    .map { it as UberTreeViewer }
            assertEquals(alternatives.size, viewers.size)
            assertTrue(viewers.all { it.treeViewer.rootTree is ParserRuleContext })
            check(viewers)
        } finally {
            Window.getWindows()
                .filterIsInstance<ShowAmbigTreesDialog>()
                .filter { it !in before }
                .forEach { it.dispose() }
        }
    }

    private fun rootLabels(viewers: List<UberTreeViewer>) = viewers.map {
        it.treeViewer.getText(it.treeViewer.rootTree)
    }
}
