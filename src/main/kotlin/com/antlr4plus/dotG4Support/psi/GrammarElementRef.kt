package com.antlr4plus.dotG4Support.psi

import com.antlr4plus.dotG4Support.TokenTypes
import com.antlr4plus.dotG4Support.psi.MyPsiUtils.findSpecNode
import com.antlr4plus.dotG4Support.references.ImportResolver.resolveImportedFile
import com.antlr4plus.dotG4Support.references.ImportResolver.resolveInImportedFiles
import com.antlr4plus.dotG4Support.references.TokenVocabResolver.resolveInTokenVocab
import com.antlr4plus.dotG4Support.references.TokenVocabResolver.resolveTokenVocabFile
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.IncorrectOperationException
import org.antlr.intellij.plugin.parser.ANTLRv4Lexer

class GrammarElementRef(idNode: GrammarElementRefNode, private val ruleName: String) :
    PsiReferenceBase<GrammarElementRefNode>(idNode, TextRange(0, ruleName.length)) {
    // Name-based references are left unchanged when binding a moved element.
    @Throws(IncorrectOperationException::class)
    override fun bindToElement(element: PsiElement): PsiElement = getElement()

    override fun getVariants(): Array<Any> {
        val rules = PsiTreeUtil.getContextOfType(myElement, RulesNode::class.java)
        return PsiTreeUtil.findChildrenOfAnyType(
                rules,
                ParserRuleSpecNode::class.java,
                LexerRuleSpecNode::class.java,
            )
            .toTypedArray()
    }

    override fun resolve(): PsiElement? {
        val tokenVocabFile = resolveTokenVocabFile(element)

        if (tokenVocabFile != null) {
            return tokenVocabFile
        }

        val importedFile = resolveImportedFile(element)
        if (importedFile != null) {
            return importedFile
        }

        val grammar = PsiTreeUtil.getContextOfType(element, GrammarSpecNode::class.java)
        val specNode = findSpecNode(grammar, ruleName) ?: resolveInImportedFiles(element.containingFile, ruleName)
        if (specNode != null) {
            return specNode
        }

        if (element is LexerRuleRefNode) {
            return resolveInTokenVocab(element, ruleName)
        }

        return null
    }

    @Throws(IncorrectOperationException::class)
    override fun handleElementRename(newElementName: String): PsiElement {
        val project = element.project
        myElement.replace(
            MyPsiUtils.createLeafFromText(
                project,
                myElement.context,
                newElementName,
                TokenTypes.TOKEN_ELEMENT_TYPES[ANTLRv4Lexer.TOKEN_REF],
            ),
        )
        return myElement
    }
}
