package com.antlr4plus.preview.tree

import org.antlr.v4.gui.TreeTextProvider
import org.antlr.v4.runtime.Vocabulary
import org.antlr.v4.runtime.misc.Utils
import org.antlr.v4.runtime.tree.Tree

class TreeModel(
    ruleNames: List<String?>?,
    private var root: Tree?,
) {
    var treeTextProvider: TreeTextProvider? = null
        internal set

    init {
        setTreeTextProvider(ruleNames)
    }

    fun setTreeTextProvider(ruleNames: List<String?>?) {
        treeTextProvider = DefaultTreeTextProvider(ruleNames)
    }

    fun setTreeTextProvider(ruleNames: List<String?>?, vocabulary: Vocabulary?) {
        treeTextProvider = DefaultTreeTextProvider(ruleNames, vocabulary)
    }

    fun setTreeTextProvider(
        ruleNames: List<String?>?,
        vocabulary: Vocabulary?,
        kotlinVocabulary: Any?,
    ) {
        treeTextProvider = DefaultTreeTextProvider(ruleNames, vocabulary, kotlinVocabulary)
    }

    fun getText(tree: Tree?): String = Utils.escapeWhitespace(treeTextProvider?.getText(tree), true)

    fun setTree(newRoot: Tree?) {
        root = newRoot
    }

    fun getRoot(): Tree? = root
}
