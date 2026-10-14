package com.antlr4plus.preview.tree

import java.awt.geom.Rectangle2D
import org.antlr.v4.runtime.tree.Tree

/** Collects node and descendant bounds, skipping branches without node bounds. */
internal fun collectSubtreeBounds(
    root: Tree,
    boundsOfNode: (Tree) -> Rectangle2D.Double?,
): Map<Tree, Rectangle2D.Double> {
    val bounds = mutableMapOf<Tree, Rectangle2D.Double>()

    class Frame(val node: Tree, val bounds: Rectangle2D.Double, var nextChild: Int = 0)

    fun frame(node: Tree): Frame? =
        boundsOfNode(node)?.let {
            Frame(node, Rectangle2D.Double(it.x, it.y, it.width, it.height))
        }

    val pending = ArrayDeque<Frame>()
    pending.addLast(frame(root) ?: return emptyMap())
    while (pending.isNotEmpty()) {
        val current = pending.last()
        if (current.nextChild < current.node.childCount) {
            val child = current.node.getChild(current.nextChild++) ?: continue
            frame(child)?.let(pending::addLast)
        } else {
            pending.removeLast()
            bounds[current.node] = current.bounds
            pending.lastOrNull()?.bounds?.add(current.bounds)
        }
    }
    return bounds
}
