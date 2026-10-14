package com.antlr4plus.parsing

import com.antlr4plus.previewService.PreviewService
import com.antlr4plus.timeline.TokenTimelineBuilder
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.antlr.v4.tool.Grammar
import org.antlr.v4.tool.LexerGrammar

class PreviewInputPermutationTest : BasePlatformTestCase() {

    fun `test all case transformation choices preserve text and select expected token`() {
        val lexer =
            lexerGrammar(
                """
                lexer grammar CaseMatrix;
                LOWER: 'abc';
                UPPER: 'ABC';
                UNICODE: [\u0080-\uFFFF]+;
                WS: [ \t\r\n]+ -> skip;
                OTHER: .;
                """
                    .trimIndent(),
            )
        val cases =
            listOf(
                Triple(CaseChangingStrategy.LEAVE_AS_IS, "abc", "LOWER"),
                Triple(CaseChangingStrategy.LEAVE_AS_IS, "ABC", "UPPER"),
                Triple(CaseChangingStrategy.FORCE_UPPERCASE, "abc", "UPPER"),
                Triple(CaseChangingStrategy.FORCE_UPPERCASE, "ABC", "UPPER"),
                Triple(CaseChangingStrategy.FORCE_LOWERCASE, "abc", "LOWER"),
                Triple(CaseChangingStrategy.FORCE_LOWERCASE, "ABC", "LOWER"),
            )

        for ((strategy, input, expectedTokenName) in cases) {
            val timeline = TokenTimelineBuilder.buildTimeline(lexer, input, strategy)
            val token = timeline.tokens.single()
            assertEquals("$strategy must preserve displayed source text", input, token.text)
            assertEquals(expectedTokenName, timeline.tokenTypeNames[token.type])
        }
    }

    fun `test case insensitive grammar automatically folds mixed-case input`() {
        val lexer =
            lexerGrammar(
                """
                lexer grammar CaseInsensitiveMatrix;
                options { caseInsensitive=true; }
                KEYWORD: 'select';
                OTHER: .;
                """
                    .trimIndent(),
            )

        val effective = CaseChangingStrategy.getEffectiveStrategy(lexer, CaseChangingStrategy.LEAVE_AS_IS)
        val timeline = TokenTimelineBuilder.buildTimeline(lexer, "SeLeCt", effective)

        assertEquals(CaseChangingStrategy.FORCE_LOWERCASE, effective)
        assertEquals("SeLeCt", timeline.tokens.single().text)
        assertEquals("KEYWORD", timeline.tokenTypeNames[timeline.tokens.single().type])
    }

    fun `test representative input data classes tokenize without exceptions`() {
        val lexer =
            lexerGrammar(
                """
                lexer grammar InputMatrix;
                WORD: [a-zA-Z]+;
                NUMBER: [0-9]+;
                UNICODE: [\u0080-\uFFFF]+;
                WS: [ \t\r\n]+ -> skip;
                OTHER: .;
                """
                    .trimIndent(),
            )
        val inputs =
            linkedMapOf(
                "empty" to "",
                "single token" to "alpha",
                "multiline" to "alpha 123\nbeta\r\n456",
                "unicode" to "café 世界",
                "otherwise invalid punctuation" to "alpha @ beta",
                "large" to List(10_000) { "word" }.joinToString(" "),
            )

        for ((label, input) in inputs) {
            for (strategy in CaseChangingStrategy.values()) {
                val result = runCatching {
                    TokenTimelineBuilder.buildTimeline(lexer, input, strategy)
                }
                assertTrue("$label/$strategy threw ${result.exceptionOrNull()}", result.isSuccess)
                val timeline = result.getOrThrow()
                if (input.isEmpty()) {
                    assertTrue(timeline.tokens.isEmpty())
                } else {
                    assertTrue("$label/$strategy produced no tokens", timeline.tokens.isNotEmpty())
                }
            }
        }
    }

    fun `test lexer modes and skipped tokens are retained for multiline data`() {
        val lexer =
            lexerGrammar(
                """
                lexer grammar ModeMatrix;
                OPEN: '<' -> pushMode(TAG);
                TEXT: [a-zA-Z]+;
                WS: [ \t\r\n]+ -> skip;
                mode TAG;
                CLOSE: '>' -> popMode;
                TAG_TEXT: [a-zA-Z]+;
                TAG_WS: [ \t\r\n]+ -> skip;
                """
                    .trimIndent(),
            )

        val timeline = TokenTimelineBuilder.buildTimeline(lexer, "before <tag name>\nafter")

        assertTrue(timeline.tokens.any { it.modeName == "TAG" })
        assertTrue(timeline.tokens.any { it.text.contains('\n') })
        assertEquals(
            listOf("DEFAULT_MODE", "TAG", "DEFAULT_MODE"),
            timeline.getModeSections().map { it.modeName },
        )
    }

    fun `test preview start rule permutations prefer EOF then common names then first rule`() {
        val state = project.getService(PreviewService::class.java).state

        state.grammar =
            Grammar(
                """
                grammar EofChoice;
                helper: ID;
                entry: ID EOF;
                ID: [a-z]+;
                """
                    .trimIndent(),
            )
        state.startRuleName = null
        ParsingUtils.autoSetStartRule(state)
        assertEquals("entry", state.startRuleName)

        state.grammar =
            Grammar(
                """
                grammar CommonChoice;
                helper: ID;
                program: ID;
                ID: [a-z]+;
                """
                    .trimIndent(),
            )
        state.startRuleName = null
        ParsingUtils.autoSetStartRule(state)
        assertEquals("program", state.startRuleName)

        state.grammar =
            Grammar(
                """
                grammar FirstChoice;
                firstRule: ID;
                secondRule: ID;
                ID: [a-z]+;
                """
                    .trimIndent(),
            )
        state.startRuleName = null
        ParsingUtils.autoSetStartRule(state)
        assertEquals("firstRule", state.startRuleName)

        state.startRuleName = "secondRule"
        ParsingUtils.autoSetStartRule(state)
        assertEquals("secondRule", state.startRuleName)
    }

    fun `test Java and Kotlin case transformation implementations stay equivalent`() {
        val samples = listOf("", "abc XYZ", "Straße", "İstanbul", "line1\nLine2", "世界")
        for (strategy in CaseChangingStrategy.values()) {
            for (sample in samples) {
                val javaStream = strategy.applyTo(org.antlr.v4.runtime.CharStreams.fromString(sample))
                val javaText =
                    if (sample.isEmpty()) {
                        ""
                    } else {
                        javaStream?.getText(
                            org.antlr.v4.runtime.misc.Interval.of(0, sample.length - 1),
                        ) ?: sample
                    }
                // CaseChangingCharStream deliberately preserves getText(), so compare lookahead
                // behavior.
                val lookahead = buildString {
                    val stream =
                        strategy.applyTo(org.antlr.v4.runtime.CharStreams.fromString(sample))
                            ?: org.antlr.v4.runtime.CharStreams.fromString(sample)
                    while (stream.LA(1) != org.antlr.v4.runtime.IntStream.EOF) {
                        append(stream.LA(1).toChar())
                        stream.consume()
                    }
                }
                assertEquals(sample, javaText)
                assertEquals("$strategy/$sample", lookahead, strategy.applyToText(sample))
            }
        }
    }

    private fun lexerGrammar(text: String): LexerGrammar =
        LexerGrammar(text).also { grammar ->
            grammar.tool.process(grammar, false)
        }
}
