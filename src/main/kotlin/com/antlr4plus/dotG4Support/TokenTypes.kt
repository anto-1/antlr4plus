package com.antlr4plus.dotG4Support

import com.antlr4plus.dotG4Support.adaptors.ANTLRLexerAdaptorImpl
import com.antlr4plus.dotG4Support.language.ANTLRv4PLanguage
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet
import org.antlr.intellij.adaptor.lexer.PSIElementTypeFactory
import org.antlr.intellij.adaptor.lexer.RuleIElementType
import org.antlr.intellij.adaptor.lexer.TokenIElementType
import org.antlr.intellij.plugin.parser.ANTLRv4Lexer
import org.antlr.intellij.plugin.parser.ANTLRv4Parser
import org.intellij.lang.annotations.MagicConstant

object TokenTypes {
    var BAD_TOKEN_TYPE: IElementType = IElementType("BAD_TOKEN", ANTLRv4PLanguage.INSTANCE)

    init {
        ANTLRLexerAdaptorImpl.initializeElementTypeFactory()
    }

    @JvmField
    val TOKEN_ELEMENT_TYPES: MutableList<TokenIElementType> =
        PSIElementTypeFactory.getTokenIElementTypes(
            ANTLRv4PLanguage.INSTANCE,
        )

    @JvmField
    val RULE_ELEMENT_TYPES: MutableList<RuleIElementType> =
        PSIElementTypeFactory.getRuleIElementTypes(ANTLRv4PLanguage.INSTANCE)

    @JvmField
    val COMMENTS: TokenSet =
        PSIElementTypeFactory.createTokenSet(
            ANTLRv4PLanguage.INSTANCE,
            ANTLRv4Lexer.DOC_COMMENT,
            ANTLRv4Lexer.BLOCK_COMMENT,
            ANTLRv4Lexer.LINE_COMMENT,
        )

    @JvmField
    val WHITESPACES: TokenSet =
        PSIElementTypeFactory.createTokenSet(
            ANTLRv4PLanguage.INSTANCE,
            ANTLRv4Lexer.WS,
        )

    val KEYWORDS: TokenSet =
        PSIElementTypeFactory.createTokenSet(
            ANTLRv4PLanguage.INSTANCE,
            ANTLRv4Lexer.LEXER,
            ANTLRv4Lexer.PROTECTED,
            ANTLRv4Lexer.IMPORT,
            ANTLRv4Lexer.CATCH,
            ANTLRv4Lexer.PRIVATE,
            ANTLRv4Lexer.FRAGMENT,
            ANTLRv4Lexer.PUBLIC,
            ANTLRv4Lexer.MODE,
            ANTLRv4Lexer.FINALLY,
            ANTLRv4Lexer.RETURNS,
            ANTLRv4Lexer.THROWS,
            ANTLRv4Lexer.GRAMMAR,
            ANTLRv4Lexer.LOCALS,
            ANTLRv4Lexer.PARSER,
        )

    @JvmStatic
    fun getRuleElementType(
        @MagicConstant(valuesFromClass = ANTLRv4Parser::class) ruleIndex: Int,
    ): RuleIElementType = RULE_ELEMENT_TYPES[ruleIndex]

    @JvmStatic
    fun getTokenElementType(
        @MagicConstant(valuesFromClass = ANTLRv4Lexer::class) ruleIndex: Int,
    ): TokenIElementType = TOKEN_ELEMENT_TYPES[ruleIndex]
}
