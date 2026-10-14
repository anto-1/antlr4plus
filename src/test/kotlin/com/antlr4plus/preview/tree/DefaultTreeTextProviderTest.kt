package com.antlr4plus.preview.tree

import com.antlr4plus.parsing.kotlin.KotlinTreeAdapter
import com.intellij.openapi.progress.ProcessCanceledException
import java.lang.reflect.UndeclaredThrowableException
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import junit.framework.TestCase
import org.antlr.v4.runtime.CommonToken
import org.antlr.v4.runtime.VocabularyImpl
import org.antlr.v4.runtime.tree.TerminalNodeImpl

/** Uses real Kotlin runtime terminals to exercise the public tree-label path. */
class DefaultTreeTextProviderTest : TestCase() {
    fun testKotlinVocabularyPropagatesControlFlowAndCancellation() {
        for (expected in listOf(ProcessCanceledException(), CancellationException("cancelled label"))) {
            checkVocabularyFailure(expected, expected)
        }
    }

    fun testKotlinVocabularyPropagatesFatalFailures() {
        val expected = TestFatalError()
        checkVocabularyFailure(expected, expected)
    }

    fun testNestedVocabularyFailuresRetainOriginalIdentity() {
        for (expected in listOf(ProcessCanceledException(), CancellationException("wrapped label"), TestFatalError())) {
            val wrapped = CompletionException(ExecutionException(UndeclaredThrowableException(expected)))
            checkVocabularyFailure(expected, wrapped)
        }
    }

    private fun checkVocabularyFailure(expected: Throwable, thrown: Throwable) {
        val vocabulary = VocabularyProbe(failure = thrown)
        val provider = DefaultTreeTextProvider(emptyList(), kotlinVocabulary = vocabulary)
        assertPropagates(expected) { provider.getText(kotlinTerminal(1, "x")) }
        assertEquals("The vocabulary is read once before propagating its failure", 1, vocabulary.calls)
    }

    fun testOrdinaryAndMissingVocabularyFailuresKeepRawText() {
        val node = kotlinTerminal(1, "x")
        for (vocabulary in listOf(null, Any(), WrongTypeVocabulary(), VocabularyProbe())) {
            assertEquals("x", DefaultTreeTextProvider(emptyList(), kotlinVocabulary = vocabulary).getText(node))
        }
        for (failure in
            listOf(
                IllegalStateException("unavailable name"),
                IllegalStateException(
                    "ordinary failure with diagnostic cause",
                    CancellationException("old cancellation"),
                ),
                CompletionException(IllegalArgumentException("ordinary wrapped failure")),
            )) {
            val vocabulary = VocabularyProbe(failure = failure)
            assertEquals("x", DefaultTreeTextProvider(emptyList(), kotlinVocabulary = vocabulary).getText(node))
            assertEquals(1, vocabulary.calls)
        }
    }

    fun testSuccessfulKotlinAndJavaTerminalLabelsAgree() {
        val kotlinVocabulary = VocabularyProbe(names = mapOf(1 to "ID", 2 to "IMPORT"))
        val javaVocabulary = VocabularyImpl(arrayOf(null, null, null, "'+'"), arrayOf(null, "ID", "IMPORT"))
        val kotlinProvider = DefaultTreeTextProvider(emptyList(), kotlinVocabulary = kotlinVocabulary)
        val javaProvider = DefaultTreeTextProvider(emptyList(), vocabulary = javaVocabulary)
        for ((type, text, expected) in
            listOf(
                Triple(1, "x", "ID:x"),
                Triple(1, "a b", "ID:a b"),
                Triple(2, "IMPORT", "IMPORT"),
                Triple(3, "+", "+"),
                Triple(99, "unknown", "unknown"),
                Triple(-1, "<EOF>", "<EOF>"),
            )) {
            assertEquals(expected, kotlinProvider.getText(kotlinTerminal(type, text)))
            assertEquals(expected, javaProvider.getText(TerminalNodeImpl(CommonToken(type, text))))
        }
        assertEquals(5, kotlinVocabulary.calls)
    }

    fun testEofAndNegativeTypesDoNotRequestKotlinVocabulary() {
        val vocabulary = VocabularyProbe(failure = TestFatalError())
        val provider = DefaultTreeTextProvider(emptyList(), kotlinVocabulary = vocabulary)
        assertEquals("<EOF>", provider.getText(kotlinTerminal(-1, "<EOF>")))
        assertEquals("unknown", provider.getText(kotlinTerminal(-2, "unknown")))
        assertEquals(0, vocabulary.calls)
    }

    fun testTreeModelPreservesOriginalVocabularyCancellation() {
        val expected = CancellationException("cancelled model label")
        val vocabulary = VocabularyProbe(failure = expected)
        val node = kotlinTerminal(1, "x")
        val model = TreeModel(emptyList(), node)
        model.setTreeTextProvider(emptyList(), null, vocabulary)
        assertPropagates(expected) { model.getText(node) }
        assertEquals(1, vocabulary.calls)
    }

    private fun kotlinTerminal(type: Int, text: String): KotlinTreeAdapter {
        val loader = javaClass.classLoader
        val token =
            loader
                .loadClass("org.antlr.v4.kotlinruntime.CommonToken")
                .getConstructor(Int::class.java, String::class.java)
                .newInstance(type, text)
        val terminal =
            loader
                .loadClass("org.antlr.v4.kotlinruntime.tree.TerminalNodeImpl")
                .getConstructor(loader.loadClass("org.antlr.v4.kotlinruntime.Token"))
                .newInstance(token)
        return KotlinTreeAdapter.wrap(terminal, loader)!!
    }

    private fun assertPropagates(expected: Throwable, action: () -> Any?) {
        val actual =
            try {
                action()
                null
            } catch (failure: Throwable) {
                failure
            }
        assertSame("Expected the original ${expected.javaClass.simpleName}", expected, actual)
    }

    class TestFatalError : VirtualMachineError()

    class VocabularyProbe(private val failure: Throwable? = null, private val names: Map<Int, String> = emptyMap()) {
        var calls = 0
            private set

        fun getSymbolicName(type: Int): String? {
            calls++
            failure?.let { throw it }
            return names[type]
        }
    }

    class WrongTypeVocabulary {
        fun getSymbolicName(type: Int): Int = type
    }
}
