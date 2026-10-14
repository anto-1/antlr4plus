package com.antlr4plus.preview

import com.antlr4plus.parsing.kotlin.KotlinTreeAdapter
import com.antlr4plus.util.escapeWhitespace
import javax.swing.tree.DefaultMutableTreeNode
import org.antlr.v4.runtime.tree.TerminalNode
import org.antlr.v4.runtime.tree.Tree

internal fun formatTreeText(
    root: Tree?,
    getText: (Tree) -> String,
): String {
    root ?: return ""
    return formatTree(root, { it.childCount }, { node, index -> node.getChild(index) }) {
        formatNodeText(it, getText(it))
    }
}

internal fun formatHierarchyText(
    root: DefaultMutableTreeNode?,
    getText: (Tree) -> String?,
): String {
    root ?: return ""
    if (root.userObject !is Tree) return ""
    return formatTree(root, { it.childCount }, { node, index -> node.getChildAt(index) as DefaultMutableTreeNode }) {
        val tree = it.userObject as? Tree
        val text = if (tree != null) getText(tree) ?: tree.toString() else it.userObject?.toString() ?: "null"
        formatNodeText(tree, text)
    }
}

/** Keep one prefix and one frame per ancestor; copying prefixes at each level needs quadratic auxiliary storage. */
private fun <N : Any> formatTree(
    root: N,
    childCount: (N) -> Int,
    childAt: (N, Int) -> N?,
    getText: (N) -> String,
): String {
    class Frame(val node: N, val children: Int, var nextChild: Int = 0)

    val text = StringBuilder().appendLine(getText(root))
    val prefix = StringBuilder()
    val pending = ArrayDeque<Frame>()
    pending.addLast(Frame(root, childCount(root)))
    while (pending.isNotEmpty()) {
        val current = pending.last()
        if (current.nextChild >= current.children) {
            pending.removeLast()
            if (pending.isNotEmpty()) prefix.setLength(prefix.length - 4)
            continue
        }
        val index = current.nextChild++
        val child = childAt(current.node, index) ?: continue
        val last = index == current.children - 1
        text.append(prefix).append(if (last) "└── " else "├── ").appendLine(getText(child))
        prefix.append(if (last) "    " else "│   ")
        pending.addLast(Frame(child, childCount(child)))
    }
    return text.toString()
}

private fun formatNodeText(tree: Tree?, text: String): String {
    val escaped = escapeWhitespace(text)
    val terminal =
        when (tree) {
            is TerminalNode -> true
            is KotlinTreeAdapter -> tree.isTerminalNode()
            else -> false
        }
    return if (terminal) "<$escaped>" else escaped
}
