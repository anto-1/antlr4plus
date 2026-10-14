package com.antlr4plus.dotG4Support.psi

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.lang.ASTNode
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.tree.IElementType
import com.intellij.util.IncorrectOperationException
import org.jetbrains.annotations.NonNls

/** Named grammar definition shared by rules, modes, tokens, and channels. */
abstract class RuleSpecNode(node: ASTNode) : ASTWrapperPsiElement(node), PsiNameIdentifierOwner {
    @JvmField protected var name: String? = null // Optional name override.

    override fun getName(): String? {
        if (name != null) return name
        val id = getNameIdentifier()
        if (id != null) {
            return id.text
        }
        return "unknown-name"
    }

    abstract override fun getNameIdentifier(): GrammarElementRefNode?

    @Throws(IncorrectOperationException::class)
    override fun setName(@NonNls name: String): PsiElement {
        // Parse replacement text to obtain a valid PSI leaf.
        getNameIdentifier()
            ?.replace(
                MyPsiUtils.createLeafFromText(
                    project,
                    context,
                    name,
                    this.ruleRefType,
                ),
            )
        this.name = name
        return this
    }

    abstract val ruleRefType: IElementType

    override fun subtreeChanged() {
        super.subtreeChanged()
        name = null
    }

    override fun getTextOffset(): Int = getNameIdentifier()?.textOffset ?: super.getTextOffset()
}
