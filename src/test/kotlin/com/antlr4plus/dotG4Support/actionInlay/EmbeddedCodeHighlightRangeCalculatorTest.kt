package com.antlr4plus.dotG4Support.actionInlay

import com.antlr4plus.parsing.embedded.EmbeddedCode
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.Test

class EmbeddedCodeHighlightRangeCalculatorTest {

    @Test
    fun `includes action braces when body offsets are available`() {
        val content = "rule : ID { foo(); } ;"
        val block =
            EmbeddedCode(
                type = EmbeddedCode.CodeType.PARSER_ACTION,
                content = " foo(); ",
                startLine = 1,
                startColumn = content.indexOf('{'),
                endLine = 1,
                endColumn = content.indexOf('}') + 1,
                scopeName = "rule",
                bodyStartOffset = content.indexOf('{') + 1,
                bodyEndOffsetExclusive = content.indexOf('}'),
            )

        val range = EmbeddedCodeHighlightRangeCalculator.calculate(block, content)
        assertNotNull(range)
        assertEquals("{ foo(); }", content.substring(range.first, range.last + 1))
    }

    @Test
    fun `includes arg block brackets when body offsets are available`() {
        val content = "rule[int value] : ID ;"
        val block =
            EmbeddedCode(
                type = EmbeddedCode.CodeType.ARGUMENT_BLOCK,
                content = "int value",
                startLine = 1,
                startColumn = content.indexOf('['),
                endLine = 1,
                endColumn = content.indexOf(']') + 1,
                scopeName = "rule",
                contextInfo = "arguments",
                bodyStartOffset = content.indexOf('[') + 1,
                bodyEndOffsetExclusive = content.indexOf(']'),
            )

        val range = EmbeddedCodeHighlightRangeCalculator.calculate(block, content)
        assertNotNull(range)
        assertEquals("[int value]", content.substring(range.first, range.last + 1))
    }

    @Test
    fun `falls back to line column ranges when body offsets are unavailable`() {
        val content =
            """
            grammar Demo;

            @members {
                int count;
            }
            """
                .trimIndent()

        val startColumn = content.lines()[2].indexOf('{')
        val endColumn = content.lines()[4].indexOf('}') + 1
        val block =
            EmbeddedCode(
                type = EmbeddedCode.CodeType.MEMBERS,
                content = "\n    int count;\n",
                startLine = 3,
                startColumn = startColumn,
                endLine = 5,
                endColumn = endColumn,
                scopeName = null,
            )

        val range = EmbeddedCodeHighlightRangeCalculator.calculate(block, content)
        assertNotNull(range)
        assertEquals("{\n    int count;\n}", content.substring(range.first, range.last + 1))
    }

    @Test
    fun `returns null for impossible line column ranges`() {
        val block =
            EmbeddedCode(
                type = EmbeddedCode.CodeType.UNKNOWN,
                content = "",
                startLine = 10,
                startColumn = 0,
                endLine = 10,
                endColumn = 1,
                scopeName = null,
            )

        assertNull(EmbeddedCodeHighlightRangeCalculator.calculate(block, "grammar Demo;"))
    }
}
