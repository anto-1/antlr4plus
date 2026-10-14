package com.antlr4plus.parsing

import com.antlr4plus.parsing.kotlin.KotlinTreeAdapter
import junit.framework.TestCase

class KotlinTreeAdapterTest : TestCase() {

    fun `test successful empty rule is not marked as failed`() {
        assertFalse(
            adapter(startIndex = 1, stopIndex = 0, failed = false).ruleFailedAndMatchedNothing(),
        )
    }

    fun `test failed empty rule is marked as failed`() {
        assertTrue(
            adapter(startIndex = 1, stopIndex = 0, failed = true).ruleFailedAndMatchedNothing(),
        )
    }

    fun `test failure after consuming input is not an empty failure`() {
        assertFalse(
            adapter(startIndex = 1, stopIndex = 1, failed = true).ruleFailedAndMatchedNothing(),
        )
    }

    // The Kotlin runtime is a runtime-only dependency, accessed reflectively by the plugin.
    private fun adapter(startIndex: Int, stopIndex: Int, failed: Boolean): KotlinTreeAdapter {
        val loader = javaClass.classLoader
        val contextClass = loader.loadClass("org.antlr.v4.kotlinruntime.ParserRuleContext")
        val tokenClass = loader.loadClass("org.antlr.v4.kotlinruntime.Token")
        val commonTokenClass = loader.loadClass("org.antlr.v4.kotlinruntime.CommonToken")
        val context = contextClass.getConstructor().newInstance()

        fun token(index: Int): Any =
            commonTokenClass.getConstructor(Int::class.javaPrimitiveType).newInstance(1).also {
                commonTokenClass.getMethod("setTokenIndex", Int::class.javaPrimitiveType).invoke(it, index)
            }

        contextClass.getMethod("setStart", tokenClass).invoke(context, token(startIndex))
        contextClass.getMethod("setStop", tokenClass).invoke(context, token(stopIndex))
        if (failed) {
            val exceptionClass = loader.loadClass("org.antlr.v4.kotlinruntime.RecognitionException")
            val constructor = exceptionClass.constructors.single { it.parameterCount == 4 }
            val input =
                loader
                    .loadClass("org.antlr.v4.kotlinruntime.ANTLRInputStream")
                    .getConstructor(String::class.java, String::class.java)
                    .newInstance("", "test")
            contextClass.getField("exception").set(context, constructor.newInstance(null, input, context, null))
        }
        return requireNotNull(KotlinTreeAdapter.wrap(context, loader))
    }
}
