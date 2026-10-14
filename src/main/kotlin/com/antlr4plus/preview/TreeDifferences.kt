package com.antlr4plus.preview

import org.antlr.v4.runtime.ParserRuleContext
import org.antlr.v4.runtime.tree.TerminalNode
import org.antlr.v4.runtime.tree.Tree

/** Adds differing nodes from tree2, comparing rule indices and terminal content. */
internal fun findDifferingNodes(
    tree1: Tree,
    tree2: Tree,
    differingNodes: MutableSet<Tree>,
) {
    val pending = ArrayDeque<Pair<Tree?, Tree>>()
    pending.addLast(tree1 to tree2)
    while (pending.isNotEmpty()) {
        val (first, second) = pending.removeLast()
        val matchingFirst = first?.takeIf { nodesMatch(it, second) }
        val firstChildren = matchingFirst?.childCount ?: -1
        val secondChildren = second.childCount
        if (firstChildren != secondChildren) differingNodes.add(second)
        for (i in secondChildren - 1 downTo 0) {
            val child = second.getChild(i) ?: continue
            val counterpart = if (i < firstChildren) matchingFirst?.getChild(i) else null
            // Once the roots differ, highlight the complete second subtree without recursive calls.
            pending.addLast(counterpart to child)
        }
    }
}

private fun nodesMatch(node1: Tree, node2: Tree): Boolean =
    when {
        node1 is ParserRuleContext && node2 is ParserRuleContext -> node1.ruleIndex == node2.ruleIndex
        node1 is TerminalNode && node2 is TerminalNode ->
            node1.symbol.type == node2.symbol.type && node1.symbol.text == node2.symbol.text
        else -> false
    }
