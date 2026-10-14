package com.antlr4plus.dotG4Support.adaptors

import org.antlr.intellij.adaptor.lexer.ANTLRLexerState
import org.antlr.intellij.plugin.parser.ANTLRv4Lexer
import org.antlr.v4.runtime.Lexer
import org.antlr.v4.runtime.misc.IntegerStack
import org.antlr.v4.runtime.misc.MurmurHash

class ANTLRLexerStateImpl(
    mode: Int,
    modeStack: IntegerStack?,
    /** Rule/prequel context restored alongside the lexer mode. */
    val currentRuleType: Int,
) : ANTLRLexerState(mode, modeStack) {
    override fun apply(lexer: Lexer) {
        super.apply(lexer)
        if (lexer is ANTLRv4Lexer) {
            lexer.currentRuleType = this.currentRuleType
        }
    }

    override fun hashCodeImpl(): Int {
        var hash = MurmurHash.initialize()
        hash = MurmurHash.update(hash, mode)
        hash = MurmurHash.update(hash, modeStack)
        hash = MurmurHash.update(hash, this.currentRuleType)
        return MurmurHash.finish(hash, 3)
    }

    override fun equals(other: Any?): Boolean {
        if (other === this) {
            return true
        }

        if (other !is ANTLRLexerStateImpl) {
            return false
        }

        if (!super.equals(other)) {
            return false
        }

        return this.currentRuleType == other.currentRuleType
    }
}
