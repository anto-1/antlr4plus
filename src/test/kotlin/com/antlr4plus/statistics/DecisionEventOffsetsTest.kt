package com.antlr4plus.statistics

import junit.framework.TestCase
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.atn.ATNConfigSet
import org.antlr.v4.runtime.atn.DecisionEventInfo
import org.antlr.v4.tool.LexerGrammar

class DecisionEventOffsetsTest : TestCase() {
    fun `test profiler ranges translate token indices to full UTF-16 input spans`() {
        val tokens = tokens()
        assertEquals(5 to 11, decisionEventEditorRange(tokens, event(tokens, 1, 2)))
        assertEquals(0 to 3, decisionEventEditorRange(tokens, event(tokens, 0, 0)))
        assertEquals(0 to 11, decisionEventEditorRange(tokens, event(tokens, 0, 3)))
        assertEquals(12 to 11, decisionEventEditorRange(tokens, event(tokens, 3, 3)))
    }

    fun `test stale and missing decision token intervals are ignored`() {
        val tokens = tokens()
        assertNull(decisionEventEditorRange(null, event(tokens, 0, 0)))
        assertNull(decisionEventEditorRange(tokens, event(tokens, -1, 0)))
        assertNull(decisionEventEditorRange(tokens, event(tokens, 2, 1)))
        assertNull(decisionEventEditorRange(tokens, event(tokens, 0, 100)))
    }

    private fun tokens(): CommonTokenStream {
        val grammar = LexerGrammar("lexer grammar Events; WORD:[a-z]+; EMOJI:'😀'; WS:[ ]+ -> skip;")
        return CommonTokenStream(grammar.createLexerInterpreter(CharStreams.fromString("long 😀 word"))).apply {
            fill()
        }
    }

    private fun event(tokens: CommonTokenStream, start: Int, stop: Int) =
        DecisionEventInfo(0, ATNConfigSet(), tokens, start, stop, false)
}
