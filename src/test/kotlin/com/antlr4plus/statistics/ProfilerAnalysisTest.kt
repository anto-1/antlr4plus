package com.antlr4plus.statistics

import com.antlr4plus.parsing.kotlin.KotlinTreeAdapter
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import org.antlr.v4.runtime.CommonToken
import org.antlr.v4.runtime.Parser
import org.antlr.v4.runtime.ParserRuleContext
import org.antlr.v4.runtime.atn.ATN
import org.antlr.v4.runtime.atn.ATNType
import org.antlr.v4.runtime.atn.BasicBlockStartState
import org.antlr.v4.runtime.tree.ParseTree
import org.antlr.v4.runtime.tree.TerminalNodeImpl

class ProfilerAnalysisTest : BasePlatformTestCase() {
    private val log
        get() = Logger.getInstance(javaClass)

    fun testDeepJavaContextSelectionDoesNotUseTheCallStack() {
        val (root, leaf) = deepJavaTree()
        val contexts = collectMatchingRuleContexts(root, 0)
        assertEquals(50_001, contexts.size)
        assertSame(root, contexts.first())
        assertSame(leaf, contexts.last())
    }

    fun testDeepJavaDecisionCountingDoesNotUseTheCallStack() {
        val (root, _) = deepJavaTree()
        assertEquals(
            mapOf(0 to 50_001L, 1 to 50_001L, 2 to 0L),
            countDecisionInvocationsFromParseTree(parser(0, 0, 1), root),
        )
    }

    fun testDeepKotlinDecisionCountingDoesNotUseTheCallStack() {
        val nodes = KotlinContexts(javaClass.classLoader)
        val root = nodes.node(0)
        var leaf = root
        repeat(50_000) {
            val child = nodes.node(0)
            nodes.setChildren(leaf, listOf(child))
            leaf = child
        }
        assertEquals(
            mapOf(0 to 50_001L, 1 to 50_001L, 2 to 0L),
            countRuleExecutionsFromKotlinTree(wrap(root), intArrayOf(0, 0, 1), 3, log),
        )
    }

    fun testContextSelectionKeepsPreorderAndDecisionsShareContainingRuleCounts() {
        val root = RuleContext(0)
        val first = RuleContext(1).also { root.addChild(it) }
        val nested = RuleContext(1).also { first.addChild(it) }
        val other = RuleContext(2).also { root.addChild(it) }
        val last = RuleContext(1).also { other.addChild(it) }
        last.addChild(TerminalNodeImpl(CommonToken(1, "x")))
        val selected = collectMatchingRuleContexts(root, 1)
        assertEquals(3, selected.size)
        listOf(first, nested, last).forEachIndexed { index, expected -> assertSame(expected, selected[index]) }
        assertEquals(
            mapOf(0 to 1L, 1 to 3L, 2 to 3L, 3 to 1L, 4 to 0L),
            countDecisionInvocationsFromParseTree(parser(0, 1, 1, 2, 3), root),
        )
        assertTrue(collectMatchingRuleContexts(root, 99).isEmpty())
    }

    fun testKotlinCountsSkipNullChildrenTerminalsAndUnknownRulesWithoutDroppingDescendants() {
        val loader = javaClass.classLoader
        val tokenClass = loader.loadClass("org.antlr.v4.kotlinruntime.Token")
        val token =
            loader
                .loadClass("org.antlr.v4.kotlinruntime.CommonToken")
                .getConstructor(Int::class.javaPrimitiveType)
                .newInstance(1)
        val terminal =
            loader
                .loadClass("org.antlr.v4.kotlinruntime.tree.TerminalNodeImpl")
                .getConstructor(tokenClass)
                .newInstance(token)
        val nodes = KotlinContexts(loader)
        val nested = nodes.node(-1, listOf(nodes.node(1)))
        val root = nodes.node(0, listOf(null, nodes.node(1), terminal, nested, nodes.node(1)))
        assertEquals(
            mapOf(0 to 1L, 1 to 3L, 2 to 3L, 4 to 0L, 5 to 0L),
            countRuleExecutionsFromKotlinTree(wrap(root), intArrayOf(0, 1, 1, -1, 2, 3), 8, log),
        )
    }

    fun testAbsentTreesAndOptionalMetadataRetainTheirFallbacks() {
        assertNull(countDecisionInvocationsFromParseTree(parser(0), null))
        assertNull(countDecisionInvocationsFromParseTree(parser(), RuleContext(0)))
        assertNull(countRuleExecutionsFromKotlinTree(null, intArrayOf(0), 1, log))
        val kotlinTree = wrap(KotlinContexts(javaClass.classLoader).node(0))
        assertNull(countRuleExecutionsFromKotlinTree(kotlinTree, intArrayOf(-1), 1, log))
        assertNull(countRuleExecutionsFromKotlinTree(kotlinTree, intArrayOf(), 0, log))
        assertEquals(listOf(-1, -1), getKotlinRuleIndexByDecision(null, 2).toList())
        assertEquals(listOf(-1, -1), getKotlinRuleIndexByDecision(Any(), 2).toList())
        val metadata = MetadataParser(MetadataAtn(listOf(null, Any(), MetadataState(2))))
        assertEquals(listOf(-1, -1, 2, -1), getKotlinRuleIndexByDecision(metadata, 4).toList())
    }

    fun testReflectedDecisionMetadataPreservesCancellationAndFatalErrors() {
        for (failure in
            listOf(ProcessCanceledException(), CancellationException("cancel metadata"), TestFatalError())) {
            for (getter in listOf("atn", "decisions", "rule")) {
                val actual =
                    try {
                        getKotlinRuleIndexByDecision(ThrowingMetadata(failure, getter), 1)
                        null
                    } catch (caught: Throwable) {
                        caught
                    }
                assertSame("$getter must preserve the original failure", failure, actual)
            }
        }
    }

    fun testJavaContextSelectionStopsWhenCancelledDuringAWideTreeWalk() = checkTreeCancellation("selection")

    fun testJavaDecisionCountingStopsWhenCancelledDuringAWideTreeWalk() = checkTreeCancellation("java")

    fun testKotlinDecisionCountingStopsWhenCancelledDuringAWideTreeWalk() = checkTreeCancellation("kotlin")

    fun testDecisionMetadataStopsWhenCancelledDuringASequenceOfGetters() {
        var reads = 0
        val indicator = EmptyProgressIndicator()
        val state = MetadataState(0) { if (++reads == 16) indicator.cancel() }
        val metadata = MetadataParser(MetadataAtn(List(10_000) { state }))
        assertCancelled(indicator) { getKotlinRuleIndexByDecision(metadata, 10_000) }
        assertTrue("Cancellation must stop metadata retrieval; read $reads entries", reads <= 32)
    }

    private fun checkTreeCancellation(operation: String) {
        val indicator = EmptyProgressIndicator()
        var visits = 0
        var childReads = 0
        val onVisit = { if (++visits == 16) indicator.cancel() }
        val javaRoot =
            object : RuleContext(0, onVisit) {
                override fun getChildCount(): Int = 10_000

                override fun getChild(i: Int): ParseTree {
                    childReads++
                    return RuleContext(0, onVisit)
                }
            }
        val nodes = KotlinContexts(javaClass.classLoader)
        val kotlinRoot =
            nodes.node(
                0,
                object : AbstractList<Any?>() {
                    override val size: Int = 10_000

                    override fun get(index: Int): Any {
                        childReads++
                        onVisit()
                        return nodes.node(0)
                    }
                },
            )
        assertCancelled(indicator) {
            when (operation) {
                "selection" -> collectMatchingRuleContexts(javaRoot, 0)
                "java" -> countDecisionInvocationsFromParseTree(parser(0), javaRoot)
                else -> countRuleExecutionsFromKotlinTree(wrap(kotlinRoot), intArrayOf(0), 1, log)
            }
        }
        assertTrue("Cancellation must stop visiting nodes; visited $visits", visits <= 32)
        assertTrue("Cancellation must stop loading children; loaded $childReads", childReads <= 32)
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

    private fun deepJavaTree(): Pair<RuleContext, RuleContext> {
        val root = RuleContext(0)
        var leaf = root
        repeat(50_000) {
            val child = RuleContext(0)
            leaf.addChild(child)
            leaf = child
        }
        return root to leaf
    }

    private fun wrap(node: Any) = KotlinTreeAdapter.wrap(node, javaClass.classLoader)!!

    private fun parser(vararg rules: Int): Parser {
        val atn = ATN(ATNType.PARSER, 1)
        rules.forEach { rule ->
            val state = BasicBlockStartState().apply { ruleIndex = rule }
            atn.addState(state)
            atn.defineDecisionState(state)
        }
        return object : Parser(null) {
            override fun getATN(): ATN = atn

            override fun getRuleNames(): Array<String> = arrayOf("start", "item", "other", "unused")

            override fun getTokenNames(): Array<String> = arrayOf("EOF")

            override fun getGrammarFileName(): String = "ProfilerTest.g4"
        }
    }

    private open class RuleContext(private val index: Int, private val onVisit: () -> Unit = {}) : ParserRuleContext() {
        override fun getRuleIndex(): Int {
            onVisit()
            return index
        }
    }

    private class KotlinContexts(loader: ClassLoader) {
        private val contextClass = loader.loadClass("org.antlr.v4.kotlinruntime.ParserRuleContext")
        private val constructor =
            loader
                .loadClass("org.antlr.v4.kotlinruntime.InterpreterRuleContext")
                .getConstructor(contextClass, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        private val childrenField = contextClass.getField("children")

        fun node(index: Int, children: List<Any?> = emptyList()): Any =
            constructor.newInstance(null, -1, index).also { setChildren(it, children) }

        fun setChildren(node: Any, children: List<Any?>) {
            childrenField.set(node, children)
        }
    }

    class MetadataParser(private val atn: Any) {
        fun getAtn(): Any = atn
    }

    class MetadataAtn(private val states: List<Any?>) {
        fun getDecisionToState(): List<Any?> = states
    }

    class MetadataState(private val index: Int, private val onRead: () -> Unit = {}) {
        fun getRuleIndex(): Int {
            onRead()
            return index
        }
    }

    class ThrowingMetadata(private val failure: Throwable, private val getter: String) {
        fun getAtn(): Any = if (getter == "atn") throw failure else this

        fun getDecisionToState(): List<Any?> = if (getter == "decisions") throw failure else listOf(this)

        fun getRuleIndex(): Int = if (getter == "rule") throw failure else 0
    }

    class TestFatalError : VirtualMachineError()
}
