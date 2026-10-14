package com.antlr4plus.dotG4Support.syntaxHighlighting

import com.antlr4plus.dotG4Support.TokenTypes
import com.antlr4plus.dotG4Support.adaptors.ANTLRLexerAdaptorImpl
import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.HighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.psi.tree.IElementType
import org.antlr.intellij.plugin.parser.ANTLRv4Lexer

class SyntaxHighlighter : SyntaxHighlighterBase() {
    override fun getHighlightingLexer(): Lexer {
        val lexer = ANTLRv4Lexer(null)
        return ANTLRLexerAdaptorImpl(lexer)
    }

    override fun getTokenHighlights(tokenType: IElementType?): Array<TextAttributesKey?> =
        when {
            TokenTypes.KEYWORDS.contains(tokenType) -> pack(KEYWORD)
            tokenType === TokenTypes.TOKEN_ELEMENT_TYPES[ANTLRv4Lexer.TOKEN_REF] -> pack(TOKENNAME)
            tokenType === TokenTypes.TOKEN_ELEMENT_TYPES[ANTLRv4Lexer.RULE_REF] -> pack(RULENAME)
            tokenType === TokenTypes.TOKEN_ELEMENT_TYPES[ANTLRv4Lexer.STRING_LITERAL] ||
                tokenType === TokenTypes.TOKEN_ELEMENT_TYPES[ANTLRv4Lexer.UNTERMINATED_STRING_LITERAL] -> STRING_KEYS
            tokenType === TokenTypes.TOKEN_ELEMENT_TYPES[ANTLRv4Lexer.BLOCK_COMMENT] -> COMMENT_KEYS
            tokenType === TokenTypes.TOKEN_ELEMENT_TYPES[ANTLRv4Lexer.DOC_COMMENT] -> COMMENT_KEYS
            tokenType === TokenTypes.TOKEN_ELEMENT_TYPES[ANTLRv4Lexer.LINE_COMMENT] -> COMMENT_KEYS
            tokenType === TokenTypes.BAD_TOKEN_TYPE -> BAD_CHAR_KEYS
            else -> EMPTY_KEYS
        }

    companion object {
        val KEYWORD: TextAttributesKey =
            TextAttributesKey.createTextAttributesKey(
                "ANTLRv4_KEYWORD",
                DefaultLanguageHighlighterColors.KEYWORD,
            )

        @JvmField
        val RULENAME: TextAttributesKey =
            TextAttributesKey.createTextAttributesKey(
                "ANTLRv4_RULENAME",
                DefaultLanguageHighlighterColors.PARAMETER,
            )

        @JvmField
        val TOKENNAME: TextAttributesKey =
            TextAttributesKey.createTextAttributesKey(
                "ANTLRv4_TOKENNAME",
                DefaultLanguageHighlighterColors.INSTANCE_FIELD,
            )
        val STRING: TextAttributesKey =
            TextAttributesKey.createTextAttributesKey(
                "ANTLRv4_STRING",
                DefaultLanguageHighlighterColors.STRING,
            )
        val LINE_COMMENT: TextAttributesKey =
            TextAttributesKey.createTextAttributesKey(
                "ANTLRv4_LINE_COMMENT",
                DefaultLanguageHighlighterColors.LINE_COMMENT,
            )
        val DOC_COMMENT: TextAttributesKey =
            TextAttributesKey.createTextAttributesKey(
                "ANTLRv4_DOC_COMMENT",
                DefaultLanguageHighlighterColors.DOC_COMMENT,
            )
        val BLOCK_COMMENT: TextAttributesKey =
            TextAttributesKey.createTextAttributesKey(
                "ANTLRv4_BLOCK_COMMENT",
                DefaultLanguageHighlighterColors.BLOCK_COMMENT,
            )

        private val BAD_CHAR_KEYS: Array<TextAttributesKey?> = pack(HighlighterColors.BAD_CHARACTER)
        private val STRING_KEYS: Array<TextAttributesKey?> = pack(STRING)
        private val COMMENT_KEYS = arrayOf<TextAttributesKey?>(LINE_COMMENT, DOC_COMMENT, BLOCK_COMMENT)
        private val EMPTY_KEYS: Array<TextAttributesKey?> = arrayOfNulls(0)
    }
}
