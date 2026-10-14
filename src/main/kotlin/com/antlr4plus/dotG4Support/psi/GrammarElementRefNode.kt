package com.antlr4plus.dotG4Support.psi

import com.intellij.psi.impl.source.tree.LeafPsiElement
import com.intellij.psi.tree.IElementType

abstract class GrammarElementRefNode(type: IElementType, text: CharSequence) : LeafPsiElement(type, text) {
    override fun getName(): String? = text

    override fun toString(): String = javaClass.getSimpleName() + "(" + elementType + ")"
}
