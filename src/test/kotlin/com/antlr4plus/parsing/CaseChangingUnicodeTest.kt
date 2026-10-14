package com.antlr4plus.parsing

import com.antlr4plus.parsing.kotlin.kotlinCharStream
import com.antlr4plus.timeline.TokenTimelineBuilder
import junit.framework.TestCase
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.misc.Interval
import org.antlr.v4.tool.LexerGrammar

class CaseChangingUnicodeTest : TestCase() {
    fun `test Java folding preserves supplementary characters original text and stream positions`() {
        val text = "a😀𐐨ßİ"
        for (strategy in CaseChangingStrategy.values()) {
            val input = strategy.applyTo(CharStreams.fromString(text))!!
            text.codePoints().toArray().forEachIndexed { index, point ->
                val expected =
                    when (strategy) {
                        CaseChangingStrategy.FORCE_UPPERCASE -> Character.toUpperCase(point)
                        CaseChangingStrategy.FORCE_LOWERCASE -> Character.toLowerCase(point)
                        else -> point
                    }
                assertEquals(expected, input.LA(1))
                input.consume()
                assertEquals(index + 1, input.index())
                assertEquals(expected, input.LA(-1))
            }
            assertEquals(-1, input.LA(1))
            assertEquals(0, input.LA(0))
            assertEquals(text, input.getText(Interval.of(0, input.size() - 1)))
        }
    }

    fun `test text folding uses simple Unicode mapping instead of truncated expansions`() {
        assertEquals("Aßİ😀𐐀", CaseChangingStrategy.FORCE_UPPERCASE.applyToText("aßİ😀𐐨"))
        assertEquals("aßi😀𐐨", CaseChangingStrategy.FORCE_LOWERCASE.applyToText("Aßİ😀𐐀"))
    }

    fun `test isolated Kotlin lookahead folds without replacing original source text`() {
        val loader = javaClass.classLoader
        val streamClass = loader.loadClass("org.antlr.v4.kotlinruntime.CharStream")
        val intervalClass = loader.loadClass("org.antlr.v4.kotlinruntime.misc.Interval")
        val stream = kotlinCharStream("😀𐐨x", "unicode", streamClass, CaseChangingStrategy.FORCE_UPPERCASE)
        val lookahead = streamClass.getMethod("LA", Int::class.javaPrimitiveType)
        assertEquals(0x1F600, lookahead.invoke(stream, 1))
        assertEquals(0x10400, lookahead.invoke(stream, 2))
        assertEquals('X'.code, lookahead.invoke(stream, 3))
        assertEquals(-1, lookahead.invoke(stream, 4))
        val interval =
            intervalClass.getConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).newInstance(0, 2)
        assertEquals("😀𐐨x", streamClass.getMethod("getText", intervalClass).invoke(stream, interval))
        streamClass.getMethod("consume").invoke(stream)
        assertEquals(1, streamClass.getMethod("index").invoke(stream))
        assertEquals(0x1F600, lookahead.invoke(stream, -1))
    }

    fun `test Kotlin stream retains supplementary characters at EOF in every case mode`() {
        val streamClass = javaClass.classLoader.loadClass("org.antlr.v4.kotlinruntime.CharStream")
        for (strategy in CaseChangingStrategy.values()) {
            val stream = kotlinCharStream("😀", "end", streamClass, strategy)
            val lookahead = streamClass.getMethod("LA", Int::class.javaPrimitiveType)
            assertEquals(0x1F600, lookahead.invoke(stream, 1))
            assertEquals(-1, lookahead.invoke(stream, 2))
            streamClass.getMethod("consume").invoke(stream)
            assertEquals(-1, lookahead.invoke(stream, 1))
            assertEquals(0x1F600, lookahead.invoke(stream, -1))
            streamClass.getMethod("seek", Int::class.javaPrimitiveType).invoke(stream, 0)
            assertEquals(0x1F600, lookahead.invoke(stream, 1))
            assertEquals("😀", stream.toString())
        }
    }

    fun `test timeline uses editor offsets and preserves original token text after folding`() {
        val grammar =
            LexerGrammar("lexer grammar Unicode; EMOJI:'😀'; UPPER:'𐐀'; LOWER:'𐐨'; WORD:[a-zA-Z]+; WS:[ ]+ -> skip;")
        val timeline = TokenTimelineBuilder.buildTimeline(grammar, "😀𐐨 x", CaseChangingStrategy.FORCE_UPPERCASE)
        assertEquals(listOf("😀", "𐐨", " ", "x"), timeline.tokens.map { it.text })
        assertEquals(listOf(0 to 1, 2 to 3, 4 to 4, 5 to 5), timeline.tokens.map { it.startIndex to it.stopIndex })
        assertEquals("UPPER", timeline.getTokenTypeName(timeline.tokens[1]))
        assertEquals(1, timeline.tokens[1].token.startIndex)
        assertEquals(1, timeline.tokens[1].token.stopIndex)
    }
}
