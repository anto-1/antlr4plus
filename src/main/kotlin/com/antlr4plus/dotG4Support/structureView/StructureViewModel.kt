package com.antlr4plus.dotG4Support.structureView

import com.antlr4plus.dotG4Support.language.FileRoot
import com.antlr4plus.dotG4Support.psi.LexerRuleSpecNode
import com.antlr4plus.dotG4Support.psi.ParserRuleSpecNode
import com.intellij.icons.AllIcons
import com.intellij.ide.structureView.StructureViewModel.ElementInfoProvider
import com.intellij.ide.structureView.StructureViewModelBase
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.util.treeView.smartTree.ActionPresentation
import com.intellij.ide.util.treeView.smartTree.ActionPresentationData
import com.intellij.ide.util.treeView.smartTree.Sorter
import com.intellij.ide.util.treeView.smartTree.SorterUtil
import com.intellij.psi.PsiFile

class StructureViewModel(var rootElement: FileRoot) :
    StructureViewModelBase(rootElement, StructureViewElement(rootElement)), ElementInfoProvider {

    override fun getSorters(): Array<Sorter> = arrayOf(PARSER_LEXER_RULE_SORTER, Sorter.ALPHA_SORTER)

    override fun getPsiFile(): PsiFile = rootElement

    override fun isAlwaysShowsPlus(element: StructureViewTreeElement): Boolean {
        val value = element.value
        return value is FileRoot
    }

    override fun isAlwaysLeaf(element: StructureViewTreeElement): Boolean {
        val value = element.value
        return value is ParserRuleSpecNode || value is LexerRuleSpecNode
    }

    /** Match the tree element types so caret navigation can select the corresponding structure item. */
    override fun getSuitableClasses(): Array<Class<*>> =
        arrayOf(
            FileRoot::class.java,
            LexerRuleSpecNode::class.java,
            ParserRuleSpecNode::class.java,
        )

    companion object {
        // Flip the initial character's case to sort parser rules before lexer rules.
        private fun ruleTypeSortKey(text: String): String =
            if (Character.isLowerCase(text[0])) {
                text[0].uppercaseChar().toString() + text.substring(1)
            } else {
                text[0].lowercaseChar().toString() + text.substring(1)
            }

        @Suppress("DialogTitleCapitalization")
        private val PARSER_LEXER_RULE_SORTER: Sorter =
            object : Sorter {
                override fun getComparator(): Comparator<*> = Comparator { o1: Any?, o2: Any? ->
                    val s1 = SorterUtil.getStringPresentation(o1)
                    val s2 = SorterUtil.getStringPresentation(o2)
                    ruleTypeSortKey(s1).compareTo(ruleTypeSortKey(s2))
                }

                override fun isVisible(): Boolean = true

                override fun getPresentation(): ActionPresentation {
                    val name = "Sort by rule type"
                    return ActionPresentationData(name, name, AllIcons.ObjectBrowser.SortByType)
                }

                override fun getName(): String = "PARSER_LEXER_RULE_SORTER"
            }
    }
}
