package com.antlr4plus.preview

import com.antlr4plus.parsing.ruleCharacterRange
import com.antlr4plus.parsing.terminalEditorRange
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreePath
import org.antlr.v4.runtime.tree.ParseTree
import org.antlr.v4.runtime.tree.Tree

/** Descends into the first matching child, comparing the offset with inclusive token ranges. */
internal fun findTreeNodeAtOffset(tree: Tree, offset: Int): Tree? {
    if (!containsOffset(tree, offset)) return null
    var current = tree
    while (true) {
        var matchingChild: Tree? = null
        for (i in 0 until current.childCount) {
            val child = current.getChild(i) ?: continue
            if (containsOffset(child, offset)) {
                matchingChild = child
                break
            }
        }
        current = matchingChild ?: return current
    }
}

internal fun findHierarchyNodeAtOffset(
    node: DefaultMutableTreeNode,
    offset: Int,
): DefaultMutableTreeNode? {
    if (!containsOffset(node.userObject as? Tree, offset)) return null
    var current = node
    while (true) {
        var matchingChild: DefaultMutableTreeNode? = null
        for (i in 0 until current.childCount) {
            val child = current.getChildAt(i) as? DefaultMutableTreeNode ?: continue
            if (containsOffset(child.userObject as? Tree, offset)) {
                matchingChild = child
                break
            }
        }
        current = matchingChild ?: return current
    }
}

private fun containsOffset(tree: Tree?, offset: Int): Boolean =
    getTreeSelectionOffsets(tree)?.let { (start, stop) -> offset in start..stop } ?: false

/** Inclusive UTF-16 token range for tree/editor selection. */
internal fun getTreeSelectionOffsets(tree: Tree?): Pair<Int, Int>? {
    val parsed = tree as? ParseTree ?: return null
    return terminalEditorRange(parsed) ?: ruleCharacterRange(parsed)
}

internal fun getHierarchySelectionOffset(path: TreePath?): Int {
    val node = path?.lastPathComponent as? DefaultMutableTreeNode ?: return -1
    return getTreeSelectionOffsets(node.userObject as? Tree)?.first ?: -1
}
