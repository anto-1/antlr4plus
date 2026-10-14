package com.antlr4plus.state

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.antlr.v4.tool.LexerGrammar

class PreviewStateLifecycleTest : BasePlatformTestCase() {

    fun `test reset clears lexer identity as well as parser state`() {
        val state = PreviewState(project)
        try {
            state.lexerGrammar = LexerGrammar("lexer grammar PreviousLexer; TOKEN: 'x';")
            assertTrue(state.isLexerOnlyMode)

            state.reset()

            assertNull(state.lexerGrammar)
            assertFalse(state.isLexerOnlyMode)
            assertEquals(GrammarType.NONE, state.grammarType)
        } finally {
            state.dispose()
        }
    }
}
