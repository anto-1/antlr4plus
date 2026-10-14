package com.antlr4plus.dotG4Support.lineMarker

import kotlin.test.assertEquals
import org.junit.Test

class GeneratedActionNavigationOffsetCalculatorTest {

    @Test
    fun `lands on first non whitespace character inside generated range`() {
        val text =
            """
            public class DemoParser {
                void rule() {
                     hello();
                }
            }
            """
                .trimIndent()

        val line = "     hello();"
        val start = text.indexOf(line)
        val range = start until (start + line.length)

        val offset = GeneratedActionNavigationOffsetCalculator.calculate(text, range)
        assertEquals(text.indexOf("hello();"), offset)
    }

    @Test
    fun `falls back to range start when generated range is all whitespace`() {
        val text = "        "
        val range = 2 until 6

        val offset = GeneratedActionNavigationOffsetCalculator.calculate(text, range)
        assertEquals(2, offset)
    }

    @Test
    fun `uses the exact insertion offset for empty generated ranges`() {
        val text = "abc\n\ndef"
        val range = 4 until 4

        val offset = GeneratedActionNavigationOffsetCalculator.calculate(text, range)
        assertEquals(4, offset)
    }

    @Test
    fun `clamps past end ranges to end of file`() {
        val text = "abc"
        val range = 10 until 20

        val offset = GeneratedActionNavigationOffsetCalculator.calculate(text, range)
        assertEquals(text.length, offset)
    }
}
