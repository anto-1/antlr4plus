package com.antlr4plus.dotG4Support.structureView

import com.antlr4plus.dotG4Support.language.FileRoot
import com.antlr4plus.dotG4Support.psi.LexerRuleRefNode
import com.antlr4plus.dotG4Support.psi.LexerRuleSpecNode
import com.antlr4plus.dotG4Support.psi.ModeSpecNode
import com.antlr4plus.dotG4Support.psi.ParserRuleRefNode
import com.antlr4plus.dotG4Support.psi.ParserRuleSpecNode
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.util.treeView.smartTree.TreeElement
import com.intellij.navigation.ItemPresentation
import com.intellij.navigation.NavigationItem
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiRecursiveElementVisitor
import com.intellij.psi.util.PsiTreeUtil

class StructureViewElement(private val element: PsiElement) : StructureViewTreeElement {
    override fun getValue(): Any = element

    override fun navigate(requestFocus: Boolean) {
        if (element is NavigationItem) {
            element.navigate(requestFocus)
        }
    }

    override fun canNavigate(): Boolean = element is NavigationItem && element.canNavigate()

    override fun canNavigateToSource(): Boolean = element is NavigationItem && element.canNavigateToSource()

    override fun getPresentation(): ItemPresentation = ItemPresentation(element)

    override fun getChildren(): Array<TreeElement> {
        val treeElements: MutableList<TreeElement> = ArrayList()

        if (element is FileRoot) {
            object : PsiRecursiveElementVisitor() {
                    override fun visitElement(element: PsiElement) {
                        if (element is ModeSpecNode) {
                            treeElements.add(StructureViewElement(element))
                            return
                        }

                        if (element is LexerRuleSpecNode || element is ParserRuleSpecNode) {
                            val rule: PsiElement? =
                                PsiTreeUtil.findChildOfAnyType(
                                    element,
                                    LexerRuleRefNode::class.java,
                                    ParserRuleRefNode::class.java,
                                )
                            if (rule != null) {
                                treeElements.add(StructureViewElement(rule))
                            }
                        }

                        super.visitElement(element)
                    }
                }
                .visitElement(element)
        } else if (element is ModeSpecNode) {
            val lexerRules = PsiTreeUtil.getChildrenOfType(element, LexerRuleSpecNode::class.java)

            if (lexerRules != null) {
                for (lexerRule in lexerRules) {
                    val element = PsiTreeUtil.findChildOfType(lexerRule, LexerRuleRefNode::class.java) ?: continue
                    treeElements.add(StructureViewElement(element))
                }
            }
        }

        return treeElements.toTypedArray()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || javaClass != other.javaClass) return false

        val that = other as StructureViewElement

        return element == that.element
    }

    override fun hashCode(): Int = element.hashCode()
}
