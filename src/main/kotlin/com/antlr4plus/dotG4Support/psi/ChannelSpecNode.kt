package com.antlr4plus.dotG4Support.psi

import com.antlr4plus.dotG4Support.TokenTypes
import com.intellij.lang.ASTNode
import com.intellij.psi.tree.IElementType
import com.intellij.psi.util.PsiTreeUtil
import org.antlr.intellij.plugin.parser.ANTLRv4Lexer

class ChannelSpecNode(node: ASTNode) : RuleSpecNode(node) {
    override fun getNameIdentifier(): GrammarElementRefNode? =
        PsiTreeUtil.getChildOfType(this, LexerRuleRefNode::class.java)

    override val ruleRefType: IElementType
        get() = TokenTypes.TOKEN_ELEMENT_TYPES[ANTLRv4Lexer.TOKEN_REF]
}
