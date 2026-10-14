package com.antlr4plus.dotG4Support.liveTemplates

import com.antlr4plus.parsing.ParsingUtils
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import org.antlr.intellij.plugin.parser.ANTLRv4Parser
import org.antlr.v4.runtime.Token

class OutsideRuleContext : ANTLRLiveTemplateContext("Outside rule") {
    public override fun isInContext(file: PsiFile, element: PsiElement, offset: Int): Boolean {
        val tokens = ParsingUtils.tokenizeANTLRGrammar(file.text)
        val tokenUnderCursor = ParsingUtils.getTokenUnderCursor(tokens, offset) ?: return false
        val tokenIndex = tokenUnderCursor.tokenIndex
        val nextRealToken = ParsingUtils.nextRealToken(tokens, tokenIndex)
        val previousRealToken = ParsingUtils.previousRealToken(tokens, tokenIndex)

        if (nextRealToken == null || previousRealToken == null) {
            return false
        }

        val previousRealTokenType = previousRealToken.type
        val nextRealTokenType = nextRealToken.type

        if (previousRealTokenType == ANTLRv4Parser.BEGIN_ACTION) {
            // Only grammar-level named actions qualify as outside a rule.
            val prevPrevRealToken = ParsingUtils.previousRealToken(tokens, previousRealToken.tokenIndex) ?: return false
            val prevPrevPrevRealToken =
                ParsingUtils.previousRealToken(tokens, prevPrevRealToken.tokenIndex) ?: return false
            if (
                prevPrevPrevRealToken.type != ANTLRv4Parser.AT && prevPrevPrevRealToken.type != ANTLRv4Parser.COLONCOLON
            ) {
                return false
            }
        }

        val okBefore =
            previousRealTokenType == ANTLRv4Parser.RBRACE ||
                previousRealTokenType == ANTLRv4Parser.SEMI ||
                previousRealTokenType == ANTLRv4Parser.BEGIN_ACTION
        val okAfter =
            nextRealTokenType == ANTLRv4Parser.TOKEN_REF ||
                nextRealTokenType == ANTLRv4Parser.RULE_REF ||
                nextRealTokenType == Token.EOF

        return okBefore && okAfter
    }
}
