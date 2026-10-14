package com.antlr4plus.dotG4Support.psi

import com.intellij.lang.ASTNode
import com.intellij.psi.PsiElement

class RulesNode(node: ASTNode) : com.intellij.extapi.psi.ASTWrapperPsiElement(node) {
    class Factory : PsiElementFactory {
        override fun createElement(node: ASTNode): PsiElement = RulesNode(node)

        companion object {
            var INSTANCE: Factory = Factory()
        }
    }
}
