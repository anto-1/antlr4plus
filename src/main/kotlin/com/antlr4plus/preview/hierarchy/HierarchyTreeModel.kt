package com.antlr4plus.preview.hierarchy

import com.antlr4plus.parsing.kotlin.KotlinTreeAdapter
import javax.swing.tree.DefaultMutableTreeNode
import org.antlr.v4.runtime.ParserRuleContext
import org.antlr.v4.runtime.tree.Tree

internal fun wrapHierarchyTree(tree: Tree?): DefaultMutableTreeNode? {
    if (tree == null) return null
    class Frame(val tree: Tree, val node: DefaultMutableTreeNode = DefaultMutableTreeNode(tree), var nextChild: Int = 0)

    val root = Frame(tree)
    val pending = ArrayDeque<Frame>()
    pending.addLast(root)
    while (pending.isNotEmpty()) {
        val current = pending.last()
        if (
            (current.tree is ParserRuleContext || current.tree is KotlinTreeAdapter) &&
                current.nextChild < current.tree.childCount
        ) {
            current.tree.getChild(current.nextChild++)?.let { child ->
                pending.addLast(Frame(child))
            }
        } else {
            pending.removeLast()
            // Attach completed subtrees bottom-up. Swing checks ancestors on insertion, so
            // attaching each child to an already linked deep tree would take quadratic time.
            pending.lastOrNull()?.node?.add(current.node)
        }
    }
    return root.node
}
