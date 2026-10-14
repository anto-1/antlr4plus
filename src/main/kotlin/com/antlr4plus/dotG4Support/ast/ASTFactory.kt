package com.antlr4plus.dotG4Support.ast

import com.antlr4plus.dotG4Support.TokenTypes
import com.antlr4plus.dotG4Support.psi.AtAction
import com.antlr4plus.dotG4Support.psi.DelegateGrammarNode
import com.antlr4plus.dotG4Support.psi.GrammarSpecNode
import com.antlr4plus.dotG4Support.psi.LexerRuleRefNode
import com.antlr4plus.dotG4Support.psi.LexerRuleSpecNode
import com.antlr4plus.dotG4Support.psi.ModeSpecNode
import com.antlr4plus.dotG4Support.psi.OptionsSpecNode
import com.antlr4plus.dotG4Support.psi.ParserRuleRefNode
import com.antlr4plus.dotG4Support.psi.ParserRuleSpecNode
import com.antlr4plus.dotG4Support.psi.PsiElementFactory
import com.antlr4plus.dotG4Support.psi.RulesNode
import com.antlr4plus.dotG4Support.psi.StringLiteralElement
import com.antlr4plus.dotG4Support.psi.TokenSpecNode
import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.lang.ASTFactory
import com.intellij.lang.ASTNode
import com.intellij.psi.PsiElement
import com.intellij.psi.impl.source.tree.CompositeElement
import com.intellij.psi.impl.source.tree.FileElement
import com.intellij.psi.impl.source.tree.LeafElement
import com.intellij.psi.impl.source.tree.LeafPsiElement
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.IFileElementType
import org.antlr.intellij.plugin.parser.ANTLRv4Lexer
import org.antlr.intellij.plugin.parser.ANTLRv4Parser

class ASTFactory : ASTFactory() {
    override fun createComposite(type: IElementType): CompositeElement =
        if (type is IFileElementType) {
            FileElement(type, null)
        } else {
            CompositeElement(type)
        }

    /** Creates reference-aware PSI leaves for grammar tokens. */
    override fun createLeaf(type: IElementType, text: CharSequence): LeafElement =
        if (type === TokenTypes.TOKEN_ELEMENT_TYPES[ANTLRv4Lexer.RULE_REF]) {
            ParserRuleRefNode(type, text)
        } else if (type === TokenTypes.TOKEN_ELEMENT_TYPES[ANTLRv4Lexer.TOKEN_REF]) {
            LexerRuleRefNode(type, text)
        } else if (type === TokenTypes.TOKEN_ELEMENT_TYPES[ANTLRv4Lexer.STRING_LITERAL]) {
            StringLiteralElement(type, text)
        } else {
            LeafPsiElement(type, text)
        }
}

fun createInternalParseTreeNode(node: ASTNode): PsiElement {
    val tokenType = node.elementType
    val factory: PsiElementFactory? = ruleElementTypeToPsiFactory[tokenType]
    return factory?.createElement(node) ?: ASTWrapperPsiElement(node)
}

private val ruleElementTypeToPsiFactory: MutableMap<IElementType?, PsiElementFactory?> by lazy {
    HashMap<IElementType?, PsiElementFactory?>().apply {
        put(TokenTypes.RULE_ELEMENT_TYPES[ANTLRv4Parser.RULE_rules], RulesNode.Factory.INSTANCE)
        put(
            TokenTypes.RULE_ELEMENT_TYPES[ANTLRv4Parser.RULE_parserRuleSpec],
            ParserRuleSpecNode.Factory.INSTANCE,
        )
        put(
            TokenTypes.RULE_ELEMENT_TYPES[ANTLRv4Parser.RULE_lexerRule],
            LexerRuleSpecNode.Factory.INSTANCE,
        )
        put(
            TokenTypes.RULE_ELEMENT_TYPES[ANTLRv4Parser.RULE_grammarSpec],
            GrammarSpecNode.Factory.INSTANCE,
        )
        put(
            TokenTypes.RULE_ELEMENT_TYPES[ANTLRv4Parser.RULE_modeSpec],
            ModeSpecNode.Factory.INSTANCE,
        )
        put(TokenTypes.RULE_ELEMENT_TYPES[ANTLRv4Parser.RULE_action], AtAction.Factory.INSTANCE)
        put(
            TokenTypes.RULE_ELEMENT_TYPES[ANTLRv4Parser.RULE_identifier],
            TokenSpecNode.Factory.INSTANCE,
        )
        put(
            TokenTypes.RULE_ELEMENT_TYPES[ANTLRv4Parser.RULE_delegateGrammar],
            DelegateGrammarNode.Factory.INSTANCE,
        )
        put(
            TokenTypes.RULE_ELEMENT_TYPES[ANTLRv4Parser.RULE_optionsSpec],
            OptionsSpecNode.Factory.INSTANCE,
        )
    }
}
