package com.antlr4plus.dotG4Support.liveTemplates

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

class ANTLRGenericContext : ANTLRLiveTemplateContext("ANTLR") {
    override fun isInContext(file: PsiFile, element: PsiElement, offset: Int) = false
}
