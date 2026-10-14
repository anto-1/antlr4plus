package com.antlr4plus.dotG4Support.references

import com.antlr4plus.dotG4Support.TokenTypes.RULE_ELEMENT_TYPES
import com.antlr4plus.dotG4Support.psi.GrammarElementRefNode
import com.antlr4plus.dotG4Support.psi.GrammarSpecNode
import com.antlr4plus.dotG4Support.psi.MyPsiUtils
import com.antlr4plus.dotG4Support.references.TokenVocabResolver.findRelativeFile
import com.intellij.openapi.util.Condition
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiRecursiveElementVisitor
import com.intellij.psi.util.PsiTreeUtil
import org.antlr.intellij.plugin.parser.ANTLRv4Parser

object ImportResolver {
    @JvmStatic
    fun resolveImportedFile(reference: GrammarElementRefNode): PsiFile? {
        val importStatement =
            PsiTreeUtil.findFirstParent(
                reference,
                Condition { obj: PsiElement -> isImportStatement(obj) },
            )

        if (importStatement != null) {
            return findRelativeFile(reference.text, reference.containingFile)
        }

        return null
    }

    private fun isImportStatement(el: PsiElement): Boolean {
        val node = el.node
        return node != null && node.elementType === RULE_ELEMENT_TYPES[ANTLRv4Parser.RULE_delegateGrammar]
    }

    @JvmStatic
    fun resolveInImportedFiles(grammarFile: PsiFile, ruleName: String): PsiElement? =
        resolveInImportedFiles(grammarFile, ruleName, ArrayList())

    private fun resolveInImportedFiles(
        grammarFile: PsiFile,
        ruleName: String?,
        visitedFiles: MutableList<PsiFile?>,
    ): PsiElement? {
        val visitor = DelegateGrammarsVisitor()
        grammarFile.accept(visitor)

        for (importedGrammar in visitor.importedGrammars) {
            if (visitedFiles.contains(importedGrammar)) {
                continue
            }
            visitedFiles.add(importedGrammar)

            val grammar = PsiTreeUtil.getChildOfType(importedGrammar, GrammarSpecNode::class.java)
            val specNode =
                MyPsiUtils.findSpecNode(grammar, ruleName)
                    ?: resolveInImportedFiles(importedGrammar, ruleName, visitedFiles)
            if (specNode != null) {
                return specNode
            }
        }

        return null
    }

    private class DelegateGrammarsVisitor : PsiRecursiveElementVisitor() {
        var importedGrammars: MutableList<PsiFile> = ArrayList()

        override fun visitElement(element: PsiElement) {
            if (isImportStatement(element)) {
                val importedGrammar = findRelativeFile(element.text, element.containingFile)

                if (importedGrammar != null) {
                    importedGrammars.add(importedGrammar)
                }
            }
            super.visitElement(element)
        }
    }
}
