package com.antlr4plus.dotG4Support.formatting

import com.intellij.formatting.*
import com.intellij.lang.ASTNode
import com.intellij.psi.formatter.common.AbstractBlock

/** Supplies a minimal IDE formatting model; [ANTLRv4PostFormatProcessor] rewrites the text. */
class ANTLRv4FormattingModelBuilder : FormattingModelBuilder {

    override fun createModel(formattingContext: FormattingContext): FormattingModel {
        val psiFile = formattingContext.containingFile
        val codeStyleSettings = formattingContext.codeStyleSettings

        val rootBlock = MinimalBlock(formattingContext.node)

        return FormattingModelProvider.createFormattingModelForPsiFile(
            psiFile,
            rootBlock,
            codeStyleSettings,
        )
    }

    private class MinimalBlock(node: ASTNode) : AbstractBlock(node, null, null) {

        override fun buildChildren(): List<Block> = emptyList()

        override fun getIndent(): Indent = Indent.getNoneIndent()

        override fun getSpacing(child1: Block?, child2: Block): Spacing? = null

        override fun isLeaf(): Boolean = true

        override fun getChildAttributes(newChildIndex: Int): ChildAttributes =
            ChildAttributes(Indent.getNoneIndent(), null)
    }
}
