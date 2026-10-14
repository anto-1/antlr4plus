package com.antlr4plus.dotG4Support.psi

import com.antlr4plus.dotG4Support.TokenTypes
import com.intellij.lang.ASTNode
import com.intellij.openapi.diagnostic.Logger
import com.intellij.psi.PsiElement
import com.intellij.psi.tree.IElementType
import com.intellij.psi.util.PsiTreeUtil
import org.antlr.intellij.plugin.parser.ANTLRv4Lexer

class ParserRuleSpecNode(node: ASTNode) : RuleSpecNode(node) {
    override val ruleRefType: IElementType
        get() = TokenTypes.TOKEN_ELEMENT_TYPES[ANTLRv4Lexer.RULE_REF]

    override fun getNameIdentifier(): GrammarElementRefNode? {
        val rr: GrammarElementRefNode? = PsiTreeUtil.getChildOfType(this, ParserRuleRefNode::class.java)
        if (rr == null) {
            LOG.error("can't find ParserRuleRefNode child of " + this.text, null as Throwable?)
        }
        return rr
    }

    class Factory : PsiElementFactory {
        override fun createElement(node: ASTNode): PsiElement = ParserRuleSpecNode(node)

        companion object {
            var INSTANCE: Factory = Factory()
        }
    }

    companion object {
        val LOG: Logger = Logger.getInstance("org.antlr.intellij.plugin.psi.ParserRuleSpecNode")
    }
}
