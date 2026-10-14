package com.antlr4plus.preview.tree

import org.antlr.v4.runtime.tree.Tree

interface TreeSelectionListener {
    fun onTreeNodeSelected(node: Tree?)
}
