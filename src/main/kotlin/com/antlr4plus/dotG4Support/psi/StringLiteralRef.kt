package com.antlr4plus.dotG4Support.psi

import com.antlr4plus.dotG4Support.references.TokenVocabResolver.resolveTokenVocabFile
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReferenceBase
import com.intellij.util.ArrayUtilRt

class StringLiteralRef(node: StringLiteralElement) :
    PsiReferenceBase<StringLiteralElement?>(node, TextRange.from(1, node.textLength - 2)) {
    override fun getVariants(): Array<Any?> = ArrayUtilRt.EMPTY_OBJECT_ARRAY

    override fun resolve(): PsiElement? = resolveTokenVocabFile(myElement)
}
