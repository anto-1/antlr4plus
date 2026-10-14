package com.antlr4plus.parsing

import com.antlr4plus.parsing.kotlin.KotlinRuleContextAdapter
import com.antlr4plus.parsing.kotlin.KotlinTreeAdapter
import com.intellij.openapi.progress.ProcessCanceledException
import java.lang.reflect.Proxy
import java.util.concurrent.CancellationException
import junit.framework.TestCase

class KotlinReflectionCancellationTest : TestCase() {
    private val loader
        get() = javaClass.classLoader

    fun testTreeGettersPropagateCancellationAndFatalErrorsThroughReflection() {
        for (expected in failures()) {
            val adapter = KotlinTreeAdapter.wrap(ThrowingNode(expected), loader)!!
            val calls: List<() -> Any?> =
                listOf(
                    { adapter.text },
                    { adapter.getParent() },
                    { adapter.payload },
                    { adapter.childCount },
                    { adapter.getChild(0) },
                    { adapter.sourceInterval },
                    { adapter.toStringTree() },
                )
            calls.forEach { assertPropagates(expected, it) }
        }
    }

    fun testRuleContextGettersPropagateCancellationAndFatalErrorsThroughReflection() {
        for (expected in failures()) {
            val adapter = KotlinRuleContextAdapter(ThrowingNode(expected), loader)
            val calls: List<() -> Any?> =
                listOf(
                    { adapter.text },
                    { adapter.getParent() },
                    { adapter.ruleIndex },
                    { adapter.childCount },
                    { adapter.getChild(0) },
                    { adapter.sourceInterval },
                )
            calls.forEach { assertPropagates(expected, it) }
        }
    }

    fun testNodeClassificationAndRuleRangesPropagateClassLoaderCancellation() {
        for (expected in failures()) {
            val failingLoader =
                object : ClassLoader(loader) {
                    override fun loadClass(name: String): Class<*> {
                        if (name.startsWith("org.antlr.v4.kotlinruntime.")) throw expected
                        return super.loadClass(name)
                    }
                }
            val adapter = KotlinTreeAdapter.wrap(Any(), failingLoader)!!
            val calls: List<() -> Any?> =
                listOf(
                    { adapter.isTerminalNode() },
                    { adapter.isErrorNode() },
                    { adapter.isRuleContext() },
                    { adapter.isParserRuleContext() },
                    { adapter.getStart() },
                    { adapter.getStop() },
                    { adapter.getException() },
                    { adapter.ruleFailedAndMatchedNothing() },
                )
            calls.forEach { assertPropagates(expected, it) }
        }
    }

    fun testTokenInformationDoesNotTurnCancelledGettersIntoFallbackValues() {
        for (getter in
            listOf(
                "getType",
                "getText",
                "getStartIndex",
                "getStopIndex",
                "getLine",
                "getCharPositionInLine",
                "getChannel",
                "getTokenIndex",
                "getInputStream",
            )) {
            val expected = CancellationException(getter)
            val tokenClass = loader.loadClass("org.antlr.v4.kotlinruntime.Token")
            val token =
                Proxy.newProxyInstance(loader, arrayOf(tokenClass)) { proxy, method, args ->
                    if (method.name == getter) throw expected
                    when (method.name) {
                        "getText" -> "x"
                        "getType",
                        "getLine" -> 1
                        "getStartIndex",
                        "getStopIndex",
                        "getCharPositionInLine",
                        "getChannel",
                        "getTokenIndex" -> 0
                        "toString" -> "token"
                        "hashCode" -> System.identityHashCode(proxy)
                        "equals" -> proxy === args?.getOrNull(0)
                        else -> null
                    }
                }
            val terminal =
                loader
                    .loadClass("org.antlr.v4.kotlinruntime.tree.TerminalNodeImpl")
                    .getConstructor(tokenClass)
                    .newInstance(token)
            val adapter = KotlinTreeAdapter.wrap(terminal, loader)!!
            assertPropagates(expected) { tokenInfoFromTerminal(adapter) }
        }
    }

    fun testParserMetadataDoesNotHideCancellationAsMissingNamesOrStatistics() {
        for (expected in failures()) {
            val parser = ThrowingMetadata(expected)
            val result = ParsingResult.kotlin(parser, Any(), loader, emptyList())
            val calls: List<() -> Any?> =
                listOf(
                    { result.ruleNames },
                    { result.getSymbolicName(1) },
                    { result.getDisplayName(1) },
                    { result.getKotlinProfilingStats() },
                    { result.getKotlinDecisionInfoList() },
                    { result.tokenCount },
                    {
                        com.antlr4plus.benchmark.extractProfilerStats(
                            parser,
                            true,
                            com.intellij.openapi.diagnostic.Logger.getInstance(javaClass),
                        )
                    },
                )
            calls.forEach { assertPropagates(expected, it) }
        }
    }

    fun testNestedStatisticsAndTokenStreamGettersPreserveCancellation() {
        for (getter in
            listOf(
                "getTotalTimeInPrediction",
                "getTotalSLLLookaheadOps",
                "getTotalLLLookaheadOps",
                "getTotalATNLookaheadOps",
                "size",
            )) {
            val expected = CancellationException(getter)
            val parser = StatisticsParser(Statistics(expected, getter))
            val result = ParsingResult.kotlin(parser, Any(), loader, emptyList())
            assertPropagates(expected) {
                if (getter == "size") result.tokenCount else result.getKotlinProfilingStats()
            }
            assertPropagates(expected) {
                com.antlr4plus.benchmark.extractProfilerStats(
                    parser,
                    true,
                    com.intellij.openapi.diagnostic.Logger.getInstance(javaClass),
                )
            }
        }
    }

    fun testMissingOptionalRuntimeMethodsStillUseTheirExistingFallbacks() {
        val original = Any()
        val adapter = KotlinTreeAdapter.wrap(original, loader)!!
        assertEquals("", adapter.text)
        assertEquals(0, adapter.childCount)
        assertNull(adapter.getChild(0))
        assertNull(adapter.parent)
        assertSame(original, adapter.payload)
        assertEquals(-1, adapter.getRuleIndex())
        assertNull(adapter.getStart())
        assertNull(adapter.getStop())
    }

    private fun failures(): List<Throwable> =
        listOf(
            ProcessCanceledException(),
            CancellationException("cancelled reflective call"),
            TestFatalError(),
        )

    private fun assertPropagates(expected: Throwable, action: () -> Any?) {
        val actual =
            try {
                action()
                null
            } catch (failure: Throwable) {
                failure
            }
        assertSame("Expected the original " + expected.javaClass.simpleName, expected, actual)
    }

    class TestFatalError : VirtualMachineError()

    class ThrowingMetadata(private val failure: Throwable) {
        fun getRuleNames(): Array<String> = throw failure

        fun getVocabulary(): Any = throw failure

        fun getParseInfo(): Any = throw failure

        fun getTokenStream(): Any = throw failure
    }

    class StatisticsParser(private val statistics: Statistics) {
        fun getParseInfo(): Any = statistics

        fun getTokenStream(): Any = statistics
    }

    class Statistics(private val failure: Throwable, private val getter: String) {
        private fun read(name: String): Long = if (name == getter) throw failure else 0L

        fun getTotalTimeInPrediction(): Long = read("getTotalTimeInPrediction")

        fun getTotalSLLLookaheadOps(): Long = read("getTotalSLLLookaheadOps")

        fun getTotalLLLookaheadOps(): Long = read("getTotalLLLookaheadOps")

        fun getTotalATNLookaheadOps(): Long = read("getTotalATNLookaheadOps")

        fun size(): Int = read("size").toInt()
    }

    class ThrowingNode(private val failure: Throwable) {
        fun getText(): String = throw failure

        fun getParent(): Any? = throw failure

        fun getPayload(): Any? = throw failure

        fun getChildCount(): Int = throw failure

        fun getChild(index: Int): Any? = throw failure

        fun getSourceInterval(): Any? = throw failure

        fun getRuleIndex(): Int = throw failure

        fun toStringTree(): String = throw failure
    }
}
