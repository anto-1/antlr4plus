package com.antlr4plus.dotG4Support.braceMatching

import com.antlr4plus.dotG4Support.TokenTypes.getTokenElementType
import com.intellij.lang.BracePair
import com.intellij.lang.PairedBraceMatcher
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IElementType
import org.antlr.intellij.plugin.parser.ANTLRv4Lexer

class ANTLRv4BraceMatcher : PairedBraceMatcher {
    override fun getPairs(): Array<BracePair> =
        arrayOf(
            BracePair(
                getTokenElementType(ANTLRv4Lexer.LPAREN),
                getTokenElementType(ANTLRv4Lexer.RPAREN),
                false,
            ),
            BracePair(
                getTokenElementType(ANTLRv4Lexer.OPTIONS),
                getTokenElementType(ANTLRv4Lexer.RBRACE),
                true,
            ),
            BracePair(
                getTokenElementType(ANTLRv4Lexer.TOKENS),
                getTokenElementType(ANTLRv4Lexer.RBRACE),
                true,
            ),
            BracePair(
                getTokenElementType(ANTLRv4Lexer.CHANNELS),
                getTokenElementType(ANTLRv4Lexer.RBRACE),
                true,
            ),
            BracePair(
                getTokenElementType(ANTLRv4Lexer.BEGIN_ACTION),
                getTokenElementType(ANTLRv4Lexer.END_ACTION),
                false,
            ),
            BracePair(
                getTokenElementType(ANTLRv4Lexer.BEGIN_ARGUMENT),
                getTokenElementType(ANTLRv4Lexer.END_ARGUMENT),
                false,
            ),
            BracePair(
                getTokenElementType(ANTLRv4Lexer.LT),
                getTokenElementType(ANTLRv4Lexer.GT),
                false,
            ),
        )

    override fun isPairedBracesAllowedBeforeType(
        lbraceType: IElementType,
        contextType: IElementType?,
    ): Boolean = true

    override fun getCodeConstructStart(file: PsiFile?, openingBraceOffset: Int): Int = openingBraceOffset
}
