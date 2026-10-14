package com.antlr4plus.dotG4Support.psi

import com.antlr4plus.dotG4Support.TokenTypes.getTokenElementType
import com.antlr4plus.dotG4Support.adaptors.ANTLRLexerAdaptorImpl
import com.antlr4plus.dotG4Support.language.FileRoot
import com.intellij.lexer.Lexer
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.search.IndexPatternBuilder
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet
import org.antlr.intellij.plugin.parser.ANTLRv4Lexer

class IndexPatternBuilderImpl : IndexPatternBuilder {
    override fun getIndexingLexer(file: PsiFile): Lexer? =
        if (file is FileRoot) {
            val lexer = ANTLRv4Lexer(null)
            ANTLRLexerAdaptorImpl(lexer)
        } else null

    override fun getCommentTokenSet(file: PsiFile): TokenSet? =
        if (file is FileRoot) TokenSet.create(getTokenElementType(ANTLRv4Lexer.LINE_COMMENT)) else null

    override fun getCommentStartDelta(tokenType: IElementType?): Int =
        if (tokenType === getTokenElementType(ANTLRv4Lexer.LINE_COMMENT)) 2 else 0

    override fun getCommentEndDelta(tokenType: IElementType?): Int = 0
}
