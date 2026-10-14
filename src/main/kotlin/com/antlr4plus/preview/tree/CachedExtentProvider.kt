package com.antlr4plus.preview.tree

import com.antlr4plus.preview.tree.TreeViewer.FontMetricsSnapshot
import org.abego.treelayout.NodeExtentProvider
import org.antlr.v4.runtime.tree.Tree

/** Supplies cached extents so background layout does not access Swing font metrics. */
class CachedExtentProvider(
    private val extents: Map<Tree, NodeExtent>,
) : NodeExtentProvider<Tree?> {

    data class NodeExtent(val width: Double, val height: Double)

    override fun getWidth(tree: Tree?): Double = tree?.let { extents[it]?.width } ?: 100.0

    override fun getHeight(tree: Tree?): Double = tree?.let { extents[it]?.height } ?: 20.0
}

internal fun measureTreeNodeExtents(
    root: Tree,
    fontSnapshot: FontMetricsSnapshot,
    getTextForNode: (Tree?) -> String,
): Map<Tree, CachedExtentProvider.NodeExtent> {
    val extents = mutableMapOf<Tree, CachedExtentProvider.NodeExtent>()
    val pending = ArrayDeque<Tree>()
    pending.addLast(root)
    while (pending.isNotEmpty()) {
        val node = pending.removeLast()
        val text = getTextForNode(node)
        val lines = text.split("\n").filter { it.isNotEmpty() }
        val maxLineWidth = lines.maxOfOrNull { fontSnapshot.stringWidth(it) } ?: fontSnapshot.stringWidth(text)
        val width = (maxLineWidth + fontSnapshot.nodeWidthPadding * 2).toDouble()
        val height = ((fontSnapshot.fontHeight + fontSnapshot.nodeHeightPadding * 2) * maxOf(1, lines.size)).toDouble()

        extents[node] = CachedExtentProvider.NodeExtent(width, height)

        for (i in node.childCount - 1 downTo 0) {
            node.getChild(i)?.let(pending::addLast)
        }
    }
    return extents
}
