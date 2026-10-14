package com.antlr4plus.preview.tree

import org.abego.treelayout.NodeExtentProvider
import org.antlr.v4.runtime.tree.Tree

class VariableExtentProvider(var treeViewer: TreeViewer) : NodeExtentProvider<Tree?> {
    override fun getWidth(tree: Tree?): Double {
        val fontMetrics = treeViewer.getFontMetrics(treeViewer.style.mainFont)
        val s = treeViewer.getText(tree)
        return (fontMetrics.stringWidth(s) + treeViewer.nodeWidthPadding * 2).toDouble()
    }

    override fun getHeight(tree: Tree?): Double {
        val fontMetrics = treeViewer.getFontMetrics(treeViewer.style.mainFont)
        val h = fontMetrics.height + treeViewer.nodeHeightPadding * 2
        val s = treeViewer.getText(tree)
        val lines: Array<String?> = s.split("\n".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
        return (h * lines.size).toDouble()
    }
}
