package com.antlr4plus.dotG4Support.refactor

import com.antlr4plus.dotG4Support.language.ANTLRv4PLanguage
import com.antlr4plus.dotG4Support.psi.RuleSpecNode
import com.intellij.lang.refactoring.RefactoringSupportProvider
import com.intellij.psi.PsiElement

class ANTLRv4RefactoringSupport : RefactoringSupportProvider() {
    override fun isAvailable(context: PsiElement): Boolean = context.language.isKindOf(ANTLRv4PLanguage.INSTANCE)

    override fun isMemberInplaceRenameAvailable(
        element: PsiElement,
        context: PsiElement?,
    ): Boolean = element is RuleSpecNode
}
