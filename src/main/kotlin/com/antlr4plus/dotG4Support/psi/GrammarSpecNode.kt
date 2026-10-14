package com.antlr4plus.dotG4Support.psi

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.lang.ASTNode
import com.intellij.psi.PsiElement

class GrammarSpecNode(node: ASTNode) : ASTWrapperPsiElement(node) {
    class Factory : PsiElementFactory {
        override fun createElement(node: ASTNode): PsiElement = GrammarSpecNode(node)

        companion object {
            var INSTANCE: Factory = Factory()
        }
    }
}
