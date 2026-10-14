package com.antlr4plus.parsing

import com.intellij.openapi.progress.ProgressManager
import org.antlr.v4.runtime.*
import org.antlr.v4.runtime.atn.*
import org.antlr.v4.tool.Grammar
import org.antlr.v4.tool.GrammarParserInterpreter

class PreviewParser(g: Grammar, atn: ATN, input: TokenStream) : GrammarParserInterpreter(g, atn, input) {
    /** Maps input tokens to matching grammar ATN states without a custom token type. */
    @JvmField var inputTokenToStateMap: MutableMap<Token, Int>? = HashMap()

    private val lexerWatchdog = LexerWatchdog(input, this)

    private var lastSuccessfulMatchState: Int = ATNState.INVALID_STATE_NUMBER

    /** Counts decision invocations independently of the profiling simulator. */
    val decisionInvocations: MutableMap<Int, Long> = mutableMapOf()

    constructor(
        g: Grammar,
        input: TokenStream,
    ) : this(
        g,
        ATNDeserializer().deserialize(ATNSerializer.getSerialized(g.getATN()).toArray()),
        input,
    )

    override fun reset() {
        super.reset()
        inputTokenToStateMap?.clear()
        lastSuccessfulMatchState = ATNState.INVALID_STATE_NUMBER
        // The superclass constructor may call reset before this field is initialized.
        @Suppress("SENSELESS_COMPARISON")
        if (decisionInvocations != null) {
            decisionInvocations.clear()
        }
    }

    override fun createInterpreterRuleContext(
        parent: ParserRuleContext?,
        invokingStateNumber: Int,
        ruleIndex: Int,
    ): InterpreterRuleContext = PreviewInterpreterRuleContext(parent, invokingStateNumber, ruleIndex)

    override fun visitDecisionState(p: DecisionState): Int {
        ProgressManager.checkCanceled()

        if (p.numberOfTransitions > 1) {
            val decision = p.decision
            if (decision >= 0) {
                decisionInvocations[decision] = (decisionInvocations[decision] ?: 0L) + 1
            }
        }

        val predictedAlt = super.visitDecisionState(p)
        if (p.numberOfTransitions > 1) {
            if (p.decision == this.overrideDecision && this._input.index() == this.overrideDecisionInputIndex) {
                (overrideDecisionRoot as PreviewInterpreterRuleContext).isDecisionOverrideRoot = true
            }
        }
        return predictedAlt
    }

    @Throws(RecognitionException::class)
    override fun match(ttype: Int): Token? {
        lexerWatchdog.checkLexerIsNotStuck()

        val t = super.match(ttype)
        inputTokenToStateMap?.put(t, state)
        lastSuccessfulMatchState = state
        return t
    }

    @Throws(RecognitionException::class)
    override fun matchWildcard(): Token? {
        lexerWatchdog.checkLexerIsNotStuck()

        inputTokenToStateMap?.put(_input.LT(1), state)
        lastSuccessfulMatchState = state
        return super.matchWildcard()
    }
}
