package com.antlr4plus.dotG4Support.psi

import com.antlr4plus.dotG4Support.TokenTypes
import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.lang.ASTNode
import com.intellij.psi.PsiElement
import com.intellij.psi.tree.IElementType
import com.intellij.psi.util.PsiTreeUtil
import org.antlr.intellij.plugin.parser.ANTLRv4Lexer
import org.antlr.intellij.plugin.parser.ANTLRv4Parser

class TokenSpecNode(node: ASTNode) : RuleSpecNode(node) {
    override fun getNameIdentifier(): GrammarElementRefNode? =
        PsiTreeUtil.getChildOfType(this, LexerRuleRefNode::class.java)

    override val ruleRefType: IElementType
        get() = TokenTypes.TOKEN_ELEMENT_TYPES[ANTLRv4Lexer.TOKEN_REF]

    class Factory : PsiElementFactory {
        override fun createElement(node: ASTNode): PsiElement {
            val parent = node.treeParent?.treeParent
            if (parent != null) {
                if (parent.elementType === TokenTypes.RULE_ELEMENT_TYPES[ANTLRv4Parser.RULE_tokensSpec]) {
                    return TokenSpecNode(node)
                } else if (parent.elementType === TokenTypes.RULE_ELEMENT_TYPES[ANTLRv4Parser.RULE_channelsSpec]) {
                    return ChannelSpecNode(node)
                }
            }

            return ASTWrapperPsiElement(node)
        }

        companion object {
            var INSTANCE: Factory = Factory()
        }
    }
}
