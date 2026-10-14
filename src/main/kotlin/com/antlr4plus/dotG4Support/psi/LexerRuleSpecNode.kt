package com.antlr4plus.dotG4Support.psi

import com.antlr4plus.dotG4Support.TokenTypes
import com.intellij.lang.ASTNode
import com.intellij.openapi.diagnostic.Logger
import com.intellij.psi.PsiElement
import com.intellij.psi.tree.IElementType
import com.intellij.psi.util.PsiTreeUtil
import org.antlr.intellij.plugin.parser.ANTLRv4Lexer

class LexerRuleSpecNode(node: ASTNode) : RuleSpecNode(node) {
    override val ruleRefType: IElementType
        get() = TokenTypes.TOKEN_ELEMENT_TYPES[ANTLRv4Lexer.TOKEN_REF]

    override fun getNameIdentifier(): GrammarElementRefNode? {
        val tr: GrammarElementRefNode? = PsiTreeUtil.getChildOfType(this, LexerRuleRefNode::class.java)
        if (tr == null) {
            LOG.error("can't find LexerRuleRefNode child of " + this.text, null as Throwable?)
        }
        return tr
    }

    val isFragment: Boolean
        get() = node.findChildByType(TokenTypes.TOKEN_ELEMENT_TYPES[ANTLRv4Lexer.FRAGMENT]) != null

    class Factory : PsiElementFactory {
        override fun createElement(node: ASTNode): PsiElement = LexerRuleSpecNode(node)

        companion object {
            var INSTANCE: Factory = Factory()
        }
    }

    companion object {
        val LOG: Logger = Logger.getInstance("org.antlr.intellij.plugin.psi.LexerRuleSpecNode")
    }
}
