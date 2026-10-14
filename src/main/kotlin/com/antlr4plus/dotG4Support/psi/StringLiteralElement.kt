package com.antlr4plus.dotG4Support.psi

import com.antlr4plus.dotG4Support.TokenTypes.RULE_ELEMENT_TYPES
import com.intellij.psi.PsiReference
import com.intellij.psi.impl.source.tree.LeafPsiElement
import com.intellij.psi.tree.IElementType
import org.antlr.intellij.plugin.parser.ANTLRv4Parser

class StringLiteralElement(type: IElementType, text: CharSequence) : LeafPsiElement(type, text) {
    override fun getReference(): PsiReference? {
        val parent = getParent()

        if (parent != null && parent.node.elementType === RULE_ELEMENT_TYPES[ANTLRv4Parser.RULE_optionValue]) {
            return StringLiteralRef(this)
        }

        return super.getReference()
    }
}
