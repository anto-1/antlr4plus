package com.antlr4plus.error

import com.antlr4plus.parsing.InputTextCoordinates
import com.intellij.openapi.util.TextRange
import junit.framework.TestCase
import org.antlr.intellij.adaptor.parser.SyntaxError
import org.antlr.v4.runtime.CommonToken

class SyntaxErrorOffsetsTest : TestCase() {
    fun `test lexer errors after emoji and on supplementary characters use full editor ranges`() {
        val source = InputTextCoordinates("😀x\n𐐨z")
        assertEquals(TextRange(2, 3), syntaxErrorEditorRange(error(1, 1), source))
        assertEquals(TextRange(4, 6), syntaxErrorEditorRange(error(2, 0), source))
        assertEquals(TextRange(6, 7), syntaxErrorEditorRange(error(2, 1), source))
    }

    fun `test parser token ranges convert both bounds and include the complete offending token`() {
        val source = InputTextCoordinates("😀x𐐨")
        val token =
            CommonToken(1).apply {
                startIndex = 1
                stopIndex = 2
            }
        assertEquals(TextRange(2, 5), syntaxErrorEditorRange(SyntaxError(null, token, 1, 1, "error", null), source))
    }

    fun `test EOF and invalid diagnostic locations do not create broken ranges`() {
        val source = InputTextCoordinates("😀")
        assertEquals(TextRange(2, 2), syntaxErrorEditorRange(error(1, 1), source))
        assertEquals(TextRange(0, 0), syntaxErrorEditorRange(error(1, 0), InputTextCoordinates("")))
        assertNull(syntaxErrorEditorRange(error(0, 0), source))
        assertNull(syntaxErrorEditorRange(error(1, -1), source))
        assertNull(syntaxErrorEditorRange(error(1, 50), source))
        assertNull(syntaxErrorEditorRange(error(3, 0), source))
    }

    private fun error(line: Int, column: Int) = SyntaxError(null, null, line, column, "error", null)
}
