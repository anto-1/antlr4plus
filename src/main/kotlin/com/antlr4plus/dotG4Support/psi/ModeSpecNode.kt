package com.antlr4plus.dotG4Support.psi

import com.antlr4plus.dotG4Support.TokenTypes
import com.antlr4plus.dotG4Support.TokenTypes.getRuleElementType
import com.antlr4plus.dotG4Support.psi.MyPsiUtils.findFirstChildOfType
import com.intellij.lang.ASTNode
import com.intellij.psi.PsiElement
import com.intellij.psi.tree.IElementType
import org.antlr.intellij.plugin.parser.ANTLRv4Lexer
import org.antlr.intellij.plugin.parser.ANTLRv4Parser

/** Models a mode and its lexer rules; reuses RuleSpecNode for named-element behavior. */
class ModeSpecNode(node: ASTNode) : RuleSpecNode(node) {
    override val ruleRefType: IElementType
        get() = TokenTypes.TOKEN_ELEMENT_TYPES[ANTLRv4Lexer.TOKEN_REF]

    override fun getNameIdentifier(): GrammarElementRefNode? =
        findFirstChildOfType(this, getRuleElementType(ANTLRv4Parser.RULE_identifier))?.firstChild
            as? GrammarElementRefNode

    class Factory : PsiElementFactory {
        override fun createElement(node: ASTNode): PsiElement = ModeSpecNode(node)

        companion object {
            var INSTANCE: Factory = Factory()
        }
    }
}
