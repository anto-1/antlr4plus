package com.antlr4plus.dotG4Support.psi

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.lang.ASTNode
import com.intellij.psi.PsiElement

/** PSI for an options block, used to scope option completion. */
class OptionsSpecNode(node: ASTNode) : ASTWrapperPsiElement(node) {
    class Factory : PsiElementFactory {
        override fun createElement(node: ASTNode): PsiElement = OptionsSpecNode(node)

        companion object {
            val INSTANCE: Factory = Factory()
        }
    }
}
