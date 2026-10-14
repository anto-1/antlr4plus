package com.antlr4plus.preview

import com.antlr4plus.parsing.ParsingResult
import com.antlr4plus.state.PreviewState
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.Window
import java.lang.reflect.InvocationTargetException
import java.util.BitSet
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonToken
import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.ListTokenSource
import org.antlr.v4.runtime.ParserRuleContext
import org.antlr.v4.runtime.Token
import org.antlr.v4.runtime.TokenStream
import org.antlr.v4.runtime.Vocabulary
import org.antlr.v4.runtime.VocabularyImpl
import org.antlr.v4.runtime.atn.AmbiguityInfo
import org.antlr.v4.runtime.atn.LookaheadEventInfo
import org.antlr.v4.runtime.misc.ParseCancellationException
import org.antlr.v4.runtime.tree.ParseTree
import org.antlr.v4.tool.Grammar
import org.antlr.v4.tool.GrammarParserInterpreter
import org.antlr.v4.tool.LexerGrammar

class DecisionTreeReplayTest : BasePlatformTestCase() {
    fun testDeepAmbiguityReplayFindsTheNestedDecision() = checkDeepReplay(lookahead = false)

    fun testDeepLookaheadReplayFindsTheNestedDecision() = checkDeepReplay(lookahead = true)

    private fun checkDeepReplay(lookahead: Boolean) {
        val depth = 10_000
        val grammar =
            Grammar(
                "grammar DeepReplay; start: nested EOF; nested: '(' nested ')' | choice; choice: a | b; a: 'x'; b: 'x';",
            )
        val tokens = tokens(grammar, "(".repeat(depth) + "x" + ")".repeat(depth))
        val rule = grammar.getRule("choice").index
        val decision = grammar.atn.decisionToState.single { it.ruleIndex == rule }.decision
        val result =
            if (lookahead) {
                getLookaheadParseTrees(
                    grammar,
                    tokens,
                    decision,
                    LookaheadEventInfo(decision, null, 1, tokens, depth, depth, false),
                    depth,
                    depth,
                    0,
                )
            } else {
                getAmbiguousParseTrees(grammar, tokens, decision, alternatives(), depth, depth, 0)
            }
        assertEquals(2, result.trees.size)
        result.trees.forEachIndexed { index, tree ->
            assertEquals(rule, tree.ruleIndex)
            assertEquals(depth, tree.start.tokenIndex)
            assertEquals(depth, tree.stop.tokenIndex)
            assertEquals(
                grammar.getRule(if (index == 0) "a" else "b").index,
                (tree.getChild(0) as ParserRuleContext).ruleIndex,
            )
            assertSame(tree, result.decisionRoots[index])
        }
    }

    fun testDeepIdentityLookupPreservesReferenceIdentity() {
        val (root, leaf) = deepTree(99)
        assertSame(leaf, findNode(root, leaf))
        assertNull(findNode(root, Node(99)))
        assertNull(findNode(root, null))
    }

    fun testRuleSelectionKeepsPreorderAndInclusiveBounds() {
        val root = Node(0, 0, 20)
        val outer = Node(1, 2, 10).also { root.addChild(it) }
        val inner = Node(1, 3, 7).also { outer.addChild(it) }
        val sibling = Node(1, 11, 18).also { root.addChild(it) }
        assertSame(outer, findRule(root, 1, 3, 7))
        assertSame(inner, findNode(root, inner))
        assertSame(sibling, findRule(root, 1, 11, 18))
        assertNull(findRule(root, 1, 2, 18))
        assertNull(findRule(root, 2, 3, 7))
    }

    fun testFirstMatchDoesNotLoadLaterSiblings() {
        val target = Node(1)
        val root =
            object : Node(0) {
                override fun getChildCount() = 10_000

                override fun getChild(index: Int): ParseTree {
                    assertEquals("Later children must stay lazy", 0, index)
                    return target
                }
            }
        assertSame(target, findRule(root, 1, 0, 0))
        assertSame(target, findNode(root, target))
    }

    fun testFallbackRegionLookupHandlesDeepTrees() {
        val (root, leaf) = deepTree(99)
        val grammar = ReplayGrammar().apply { replayTree = root }
        val tokens = tokens(grammar)
        val result = getAmbiguousParseTrees(grammar, tokens, 0, alternatives(), 0, 0, 0)
        assertEquals(2, result.trees.size)
        result.trees.forEach { assertSame(leaf, it) }
        assertTrue(result.decisionRoots.all { it == null })
    }

    fun testFallbackKeepsLeftmostLowestContextAndOpenEndedStops() {
        val root = Node(99, 0, 9)
        val left = Node(99, 0, 9).also { root.addChild(it) }
        val leaf = Node(99, 0, 0).apply { stop = null }.also { left.addChild(it) }
        root.addChild(Node(99, 0, 9))
        val grammar = ReplayGrammar().apply { replayTree = root }
        val tokens = tokens(grammar)
        val result = getLookaheadParseTrees(grammar, tokens, 0, lookahead(tokens), 0, 0, 0)
        assertEquals(2, result.trees.size)
        result.trees.forEach { assertSame(leaf, it) }
    }

    fun testRuleAndIdentityTraversalObserveCancellation() {
        for (identity in listOf(false, true)) {
            val indicator = EmptyProgressIndicator()
            var reads = 0
            val root =
                object : Node(0) {
                    override fun getChildCount() = 10_000

                    override fun getChild(index: Int): ParseTree {
                        if (++reads == 8) indicator.cancel()
                        return Node(99)
                    }
                }
            assertCancelled(indicator) {
                if (identity) findNode(root, Node(2)) else findRule(root, 2, 0, 0)
            }
            assertTrue("Cancellation must stop child reads, received $reads", reads <= 32)
        }
    }

    fun testTokenRemappingObservesCancellationDuringVocabularyAndTokenCopy() {
        for (duringVocabulary in listOf(true, false)) {
            val indicator = EmptyProgressIndicator()
            var reads = 0
            val onRead = { if (++reads == 8) indicator.cancel() }
            val lexer = LexerGrammar("lexer grammar Tokens; A: 'a';")
            val vocabulary =
                object : Vocabulary {
                    override fun getMaxTokenType() = if (duringVocabulary) 10_000 else 0

                    override fun getLiteralName(type: Int): String? = null

                    override fun getSymbolicName(type: Int): String? {
                        if (duringVocabulary) onRead()
                        return null
                    }

                    override fun getDisplayName(type: Int) = type.toString()
                }
            val stream =
                object : CommonTokenStream(ListTokenSource(List(10_000) { CommonToken(1, "a") })) {
                        override fun get(index: Int): Token {
                            if (!duringVocabulary) onRead()
                            return super.get(index)
                        }
                    }
                    .apply { fill() }
            assertCancelled(indicator) { createRemappedTokenStream(stream, vocabulary, lexer) }
            assertTrue("Cancellation must stop remapping, received $reads reads", reads <= 32)
        }
    }

    fun testLookaheadPropagatesControlFlowAndStopsTryingAlternatives() {
        for (expected in
            listOf(ProcessCanceledException(), CancellationException("cancel replay"), ProbeFatalError())) {
            for (wrapped in listOf(false, true)) {
                val grammar =
                    ReplayGrammar().apply { failure = if (wrapped) InvocationTargetException(expected) else expected }
                val tokens = tokens(grammar)
                assertSameFailure(expected) { getLookaheadParseTrees(grammar, tokens, 0, lookahead(tokens), 0, 0, 0) }
                assertEquals(1, grammar.replayCalls)
            }
        }
    }

    fun testLookaheadStillSkipsSyntaxBailoutsAndOrdinaryFailedAlternatives() {
        for (failure in
            listOf(ParseCancellationException("syntax error"), IllegalStateException("ordinary replay failure"))) {
            val grammar = ReplayGrammar().apply { this.failure = failure }
            val tokens = tokens(grammar)
            val result = getLookaheadParseTrees(grammar, tokens, 0, lookahead(tokens), 0, 0, 0)
            assertEquals(2, grammar.replayCalls)
            assertTrue(result.trees.isEmpty())
            assertTrue(result.decisionRoots.isEmpty())
        }
    }

    fun testAmbiguityPopupPropagatesCancellationBeforeCreatingAWindow() = checkPopupCancellation(false)

    fun testLookaheadPopupPropagatesCancellationBeforeCreatingAWindow() = checkPopupCancellation(true)

    private fun checkPopupCancellation(lookahead: Boolean) {
        val grammar = ReplayGrammar()
        val tokens = tokens(grammar)
        val original = grammar.createGrammarParserInterpreter(tokens)
        val state =
            PreviewState(project).apply {
                this.grammar = grammar
                lexerGrammar = grammar.implicitLexer
                startRuleName = "start"
                parsingResult = ParsingResult.java(original, Node(0), null)
            }
        for (expected in listOf(ProcessCanceledException(), CancellationException("cancel popup"))) {
            grammar.failure = expected
            grammar.replayCalls = 0
            val windowsBefore = Window.getWindows().toSet()
            assertSameFailure(expected) {
                if (lookahead) ShowAmbigTreesDialog.popupLookaheadTreesDialog(state, lookahead(tokens))
                else
                    ShowAmbigTreesDialog.popupAmbigTreesDialog(
                        state,
                        AmbiguityInfo(0, null, alternatives(), tokens, 0, 0, false),
                    )
            }
            assertEquals(1, grammar.replayCalls)
            assertEquals("Cancelled replay must not create a window", windowsBefore, Window.getWindows().toSet())
        }
    }

    fun testRemappingPreservesTokenMetadataAndOriginalTokens() {
        val lexer = LexerGrammar("lexer grammar Mapping; A: 'a'; B: 'b';")
        val first =
            CommonToken(7, "a").apply {
                startIndex = 2
                stopIndex = 2
                line = 3
                charPositionInLine = 1
                channel = Token.HIDDEN_CHANNEL
            }
        val second =
            CommonToken(8, "b").apply {
                startIndex = 4
                stopIndex = 4
            }
        val original = CommonTokenStream(ListTokenSource(listOf(first, second))).apply { fill() }
        val names =
            arrayOfNulls<String>(9).apply {
                this[7] = "A"
                this[8] = "B"
            }
        val remapped = createRemappedTokenStream(original, VocabularyImpl(emptyArray(), names), lexer)
        assertEquals(original.size(), remapped.size())
        for (i in 0 until original.size()) {
            val before = original.get(i)
            val after = remapped.get(i)
            assertNotSame(before, after)
            assertEquals(before.text, after.text)
            assertEquals(before.startIndex, after.startIndex)
            assertEquals(before.stopIndex, after.stopIndex)
            assertEquals(before.channel, after.channel)
            assertEquals(before.line, after.line)
            assertEquals(before.charPositionInLine, after.charPositionInLine)
            assertEquals(before.tokenIndex, after.tokenIndex)
        }
        assertEquals(7, first.type)
        assertEquals(8, second.type)
        assertEquals(lexer.getTokenType("A"), remapped.get(0).type)
        assertEquals(lexer.getTokenType("B"), remapped.get(1).type)
        assertEquals(Token.EOF, remapped.get(2).type)
    }

    private fun tokens(grammar: Grammar, input: String = "x") =
        CommonTokenStream(grammar.implicitLexer.createLexerInterpreter(CharStreams.fromString(input))).apply { fill() }

    private fun lookahead(tokens: TokenStream) = LookaheadEventInfo(0, null, 1, tokens, 0, 0, false)

    private fun alternatives() =
        BitSet().apply {
            set(1)
            set(2)
        }

    private open class Node(private val rule: Int, from: Int = 0, to: Int = 0) : ParserRuleContext() {
        init {
            start = CommonToken(1).apply { tokenIndex = from }
            stop = CommonToken(1).apply { tokenIndex = to }
        }

        override fun getRuleIndex() = rule
    }

    private fun deepTree(rule: Int): Pair<Node, Node> {
        val root = Node(rule)
        var leaf = root
        repeat(50_000) { leaf = Node(rule).also { leaf.addChild(it) } }
        return root to leaf
    }

    private class ReplayGrammar : Grammar("grammar Replay; start: 'x' | 'x' 'y';") {
        var failure: Throwable? = null
        var replayTree: ParserRuleContext? = null
        var replayCalls = 0

        override fun createGrammarParserInterpreter(tokens: TokenStream): GrammarParserInterpreter {
            replayCalls++
            failure?.let { throw it }
            val parser = super.createGrammarParserInterpreter(tokens)
            return replayTree?.let { tree ->
                object : GrammarParserInterpreter(this, parser.atn, tokens) {
                    override fun parse(startRuleIndex: Int) = tree
                }
            } ?: parser
        }
    }

    private class ProbeFatalError : VirtualMachineError("synthetic replay failure")

    private fun findRule(tree: ParserRuleContext, rule: Int, start: Int, stop: Int): ParserRuleContext? =
        callPrivate(
            "findRuleContext",
            arrayOf(ParserRuleContext::class.java, Int::class.java, Int::class.java, Int::class.java),
            tree,
            rule,
            start,
            stop,
        )

    private fun findNode(tree: ParserRuleContext, target: ParserRuleContext?): ParserRuleContext? =
        callPrivate(
            "findNodeInTree",
            arrayOf(ParserRuleContext::class.java, ParserRuleContext::class.java),
            tree,
            target,
        )

    private fun callPrivate(name: String, types: Array<Class<*>>, vararg args: Any?): ParserRuleContext? =
        try {
            Class.forName("com.antlr4plus.preview.DecisionTreeReplayKt")
                .getDeclaredMethod(name, *types)
                .apply { isAccessible = true }
                .invoke(null, *args) as ParserRuleContext?
        } catch (failure: InvocationTargetException) {
            throw requireNotNull(failure.cause)
        }

    private fun assertSameFailure(expected: Throwable, action: () -> Unit) {
        val actual =
            try {
                action()
                null
            } catch (failure: Throwable) {
                failure
            }
        assertSame("Expected the original failure, received $actual", expected, actual)
    }

    private fun assertCancelled(indicator: EmptyProgressIndicator, action: () -> Unit) {
        val failure =
            ApplicationManager.getApplication()
                .executeOnPooledThread(
                    Callable {
                        try {
                            ProgressManager.getInstance().runProcess(Runnable { action() }, indicator)
                            null
                        } catch (caught: Throwable) {
                            caught
                        }
                    },
                )
                .get(30, TimeUnit.SECONDS)
        assertTrue("Expected cancellation, received $failure", failure is ProcessCanceledException)
    }
}
