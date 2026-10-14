package com.antlr4plus.dotG4Support.adaptors

import com.antlr4plus.dotG4Support.language.ANTLRv4PLanguage
import org.antlr.intellij.adaptor.lexer.ANTLRLexerAdaptor
import org.antlr.intellij.adaptor.lexer.PSIElementTypeFactory
import org.antlr.intellij.plugin.parser.ANTLRv4Lexer
import org.antlr.intellij.plugin.parser.ANTLRv4Parser
import org.antlr.v4.runtime.Lexer

class ANTLRLexerAdaptorImpl(lexer: ANTLRv4Lexer?) : ANTLRLexerAdaptor(ANTLRv4PLanguage.INSTANCE, lexer) {
    override fun getInitialState(): ANTLRLexerStateImpl = INITIAL_STATE

    override fun getLexerState(lexer: Lexer): ANTLRLexerStateImpl =
        if (lexer._modeStack.isEmpty) {
            ANTLRLexerStateImpl(lexer._mode, null, (lexer as ANTLRv4Lexer).currentRuleType)
        } else {
            ANTLRLexerStateImpl(
                lexer._mode,
                lexer._modeStack,
                (lexer as ANTLRv4Lexer).currentRuleType,
            )
        }

    companion object {
        // Initialize token types even when created outside ParserDefinition.
        init {
            initializeElementTypeFactory()
        }

        fun initializeElementTypeFactory() {
            @Suppress("DEPRECATION")
            PSIElementTypeFactory.defineLanguageIElementTypes(
                ANTLRv4PLanguage.INSTANCE,
                ANTLRv4Lexer.tokenNames,
                ANTLRv4Parser.ruleNames,
            )
        }

        private val INITIAL_STATE = ANTLRLexerStateImpl(Lexer.DEFAULT_MODE, null, 0)
    }
}
