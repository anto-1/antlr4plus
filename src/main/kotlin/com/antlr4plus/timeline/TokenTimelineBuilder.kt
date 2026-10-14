package com.antlr4plus.timeline

import com.antlr4plus.parsing.CaseChangingStrategy
import com.antlr4plus.state.PreviewState
import org.antlr.v4.runtime.CharStream
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.Token
import org.antlr.v4.tool.LexerGrammar

/** Re-lexes input with mode tracking, including skipped segments in the timeline. */
object TokenTimelineBuilder {

    /** Builds from supplied text without rereading the input file. */
    fun buildFromStateWithText(state: PreviewState, inputText: String): TokenTimeline? {
        val lexerGrammar = state.lexerGrammar ?: return null

        val userStrategy = state.liveSettings?.caseChangingStrategy ?: CaseChangingStrategy.LEAVE_AS_IS
        val effectiveStrategy = CaseChangingStrategy.getEffectiveStrategy(lexerGrammar, userStrategy)

        return buildTimeline(lexerGrammar, inputText, effectiveStrategy)
    }

    /** Captures lexer matches with interpreted mode tracking; target actions are only approximated. */
    fun buildTimeline(
        lexerGrammar: LexerGrammar,
        inputText: String,
        caseStrategy: CaseChangingStrategy =
            CaseChangingStrategy.getEffectiveStrategy(
                lexerGrammar,
                CaseChangingStrategy.LEAVE_AS_IS,
            ),
    ): TokenTimeline {
        val baseInput = CharStreams.fromString(inputText)
        val input: CharStream = caseStrategy.applyTo(baseInput) ?: baseInput

        val lexer = AllTokenCapturingLexerInterpreter.create(lexerGrammar, input)
        lexer.removeErrorListeners() // Timeline lexing must not duplicate parser diagnostics.
        while (lexer.nextToken().type != Token.EOF) {
            // nextToken records emitted and skipped matches as it advances.
        }
        return lexer.buildTimeline()
    }
}
