package com.antlr4plus.preview

import com.antlr4plus.parsing.ParsingResult
import com.antlr4plus.preview.tree.UberTreeViewer
import com.antlr4plus.state.PreviewState
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.JBColor
import java.awt.Color
import java.awt.Window
import java.util.BitSet
import javax.swing.JPanel
import javax.swing.JScrollPane
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
import org.antlr.v4.tool.Grammar
import org.antlr.v4.tool.GrammarInterpreterRuleContext

/** Exercises the actual dialog's selection and comparison baseline using real replayed alternatives. */
class LookaheadSelectionIntegrationTest : BasePlatformTestCase() {
    fun testSparseConfigurationsSelectThePredictedAlternative() =
        checkStandard(
            configurations = listOf(2, 3),
            predicted = 2,
            expectedSelection = 0,
        )

    fun testFailedEarlierAlternativeDoesNotShiftThePrediction() =
        checkStandard(
            configurations = null,
            predicted = 2,
            expectedSelection = 0,
        )

    fun testFailedPredictedAlternativeDoesNotHighlightAnotherTree() =
        checkStandard(
            configurations = null,
            predicted = 1,
            expectedSelection = -1,
        )

    fun testLastSurvivingAlternativeKeepsItsSelection() =
        checkStandard(
            configurations = listOf(2, 3),
            predicted = 3,
            expectedSelection = 1,
        )

    fun testCompleteAlternativeListKeepsItsSelection() =
        checkStandard(
            configurations = null,
            predicted = 2,
            expectedSelection = 1,
            firstMatches = true,
        )

    private fun checkStandard(
        configurations: List<Int>?,
        predicted: Int,
        expectedSelection: Int,
        firstMatches: Boolean = false,
    ) {
        val grammar =
            Grammar(
                "grammar Choices; start: choice EOF; choice: first | second | third; first: '${if (firstMatches) "x" else "a"}'; second: 'x'; third: 'x';",
            )
        val fixture = prepare(grammar, "x", "choice", transitions = 3)
        val event = event(fixture, configurations, predicted, 0)
        val replay = replay(fixture, grammar, event)
        assertEquals(if (firstMatches) listOf(1, 2, 3) else listOf(2, 3), replay.alternativeNumbers)
        withDialog({ ShowAmbigTreesDialog.popupLookaheadTreesDialog(fixture.state, event) }) { dialog ->
            val viewers = viewers(dialog)
            val expectedRules = if (firstMatches) listOf("first", "second", "third") else listOf("second", "third")
            assertEquals(
                expectedRules,
                viewers.map { viewer ->
                    val tree = viewer.treeViewer.rootTree as ParserRuleContext
                    grammar.getRule((tree.getChild(0) as ParserRuleContext).ruleIndex).name
                },
            )
            assertSelection(dialog, expectedSelection)
            viewers.forEachIndexed { index, viewer ->
                if (expectedSelection < 0 || index == expectedSelection) {
                    assertTrue(
                        "No comparison may use an absent prediction or mark the predicted tree",
                        viewer.treeViewer.differingNodes.isNullOrEmpty(),
                    )
                } else {
                    assertFalse(
                        "Other alternatives must be compared with the predicted tree",
                        viewer.treeViewer.differingNodes.isNullOrEmpty(),
                    )
                }
            }
        }
    }

    fun testLeftRecursiveSparseAlternativesUseDecisionNumbers() = checkLeftRecursive(listOf(2, 3), listOf(3, 4))

    fun testLeftRecursiveSingleAlternativeKeepsItsDecisionNumber() = checkLeftRecursive(listOf(2), listOf(3))

    private fun checkLeftRecursive(configurations: List<Int>, expectedOuterAlternatives: List<Int>) {
        val grammar =
            Grammar(
                "grammar Expressions; start: expr EOF; expr: INT | expr '*' expr | expr '+' expr | expr '+' expr; INT: [0-9]+;",
            )
        val fixture = prepare(grammar, "1+2", "expr", transitions = 3)
        val event = event(fixture, configurations, predicted = 2, tokenIndex = 1)
        assertEquals(configurations, replay(fixture, grammar, event).alternativeNumbers)
        withDialog({ ShowAmbigTreesDialog.popupLookaheadTreesDialog(fixture.state, event) }) { dialog ->
            val actualOuterAlternatives =
                viewers(dialog).map { (it.treeViewer.rootTree as GrammarInterpreterRuleContext).outerAltNum }
            assertEquals(
                "Decision alternatives differ from displayed outer rule alternatives",
                expectedOuterAlternatives,
                actualOuterAlternatives,
            )
            assertSelection(dialog, 0)
        }
    }

    fun testSparseAmbiguityStillSelectsTheFirstValidAlternative() {
        val grammar =
            Grammar(
                "grammar Ambiguous; start: choice EOF; choice: first | second | third; first: 'a'; second: 'x'; third: 'x';",
            )
        val fixture = prepare(grammar, "x", "choice", transitions = 3)
        val info =
            AmbiguityInfo(
                fixture.decision.decision,
                null,
                BitSet().apply {
                    set(2)
                    set(3)
                },
                fixture.tokens,
                0,
                0,
                true,
            )
        val replay =
            getAmbiguousParseTrees(
                grammar,
                fixture.tokens,
                info.decision,
                info.ambigAlts,
                info.startIndex,
                info.stopIndex,
                grammar.getRule("start").index,
            )
        assertEquals(listOf(2, 3), replay.alternativeNumbers)
        withDialog({ ShowAmbigTreesDialog.popupAmbigTreesDialog(fixture.state, info) }) { dialog ->
            assertEquals(2, viewers(dialog).size)
            assertSelection(dialog, 0)
        }
    }

    private data class Fixture(val state: PreviewState, val tokens: CommonTokenStream, val decision: DecisionState)

    private fun replay(fixture: Fixture, grammar: Grammar, event: LookaheadEventInfo) =
        getLookaheadParseTrees(
                grammar,
                fixture.tokens,
                event.decision,
                event,
                event.startIndex,
                event.stopIndex,
                grammar.getRule("start").index,
            )
            .also {
                assertEquals(it.trees.size, it.alternativeNumbers.size)
                assertEquals(it.trees.size, it.decisionRoots.size)
            }

    private fun prepare(grammar: Grammar, input: String, rule: String, transitions: Int): Fixture {
        val tokens =
            CommonTokenStream(grammar.implicitLexer.createLexerInterpreter(CharStreams.fromString(input))).apply {
                fill()
            }
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

    private fun event(fixture: Fixture, alternatives: List<Int>?, predicted: Int, tokenIndex: Int): LookaheadEventInfo {
        val configurations = alternatives?.let { numbers ->
            ATNConfigSet().apply {
                numbers.forEach { add(ATNConfig(fixture.decision, it, EmptyPredictionContext.Instance)) }
            }
        }
        return LookaheadEventInfo(
            fixture.decision.decision,
            configurations,
            predicted,
            fixture.tokens,
            tokenIndex,
            tokenIndex,
            false,
        )
    }

    private fun withDialog(open: () -> Unit, check: (ShowAmbigTreesDialog) -> Unit) {
        val before = Window.getWindows().toSet()
        try {
            open()
            val dialogs = Window.getWindows().filterIsInstance<ShowAmbigTreesDialog>().filter { it !in before }
            assertEquals("Expected one replay dialog", 1, dialogs.size)
            check(dialogs.single())
        } finally {
            Window.getWindows()
                .filterIsInstance<ShowAmbigTreesDialog>()
                .filter { it !in before }
                .forEach { it.dispose() }
        }
    }

    private fun viewers(dialog: ShowAmbigTreesDialog): List<UberTreeViewer> =
        (dialog.javaClass.getDeclaredField("uberViewers").apply { isAccessible = true }.get(dialog) as Array<*>).map {
            it as UberTreeViewer
        }

    private fun assertSelection(dialog: ShowAmbigTreesDialog, expected: Int) {
        val scrollPane =
            dialog.javaClass.getDeclaredField("treeScrollPane").apply { isAccessible = true }.get(dialog) as JScrollPane
        val wrappers = (scrollPane.viewport.view as JPanel).components.filterIsInstance<JPanel>()
        assertEquals(viewers(dialog).size, wrappers.size)
        val chosenColor = JBColor(Color.WHITE, Color(60, 63, 65)).rgb
        val selected = wrappers.indices.filter { wrappers[it].background.rgb == chosenColor }
        assertEquals(
            "The dialog must highlight the predicted decision alternative",
            if (expected < 0) emptyList<Int>() else listOf(expected),
            selected,
        )
    }
}
