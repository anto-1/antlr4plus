package com.antlr4plus.dotG4Support.psi

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.lang.ASTNode

/** An individual imported grammar, optionally with an alias. */
class DelegateGrammarNode(node: ASTNode) : ASTWrapperPsiElement(node) {

    class Factory : PsiElementFactory {
        override fun createElement(node: ASTNode) = DelegateGrammarNode(node)

        companion object {
            val INSTANCE: Factory = Factory()
        }
    }
}
