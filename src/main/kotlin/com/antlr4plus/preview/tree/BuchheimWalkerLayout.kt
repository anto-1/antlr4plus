package com.antlr4plus.preview.tree

import java.awt.geom.Rectangle2D
import org.abego.treelayout.NodeExtentProvider
import org.antlr.v4.runtime.tree.Tree

/**
 * Variable-width tree layout based on "Improving Walker's Algorithm to Run in Linear Time" by Christoph Buchheim,
 * Michael Jünger, and Sebastian Leipert (2002).
 */
class BuchheimWalkerLayout<T : Tree>(
    private val root: T,
    private val extentProvider: NodeExtentProvider<T?>,
    private val horizontalGap: Double = HardcodedConfigs.GAP_BETWEEN_NODES,
    private val verticalSpacing: Double = HardcodedConfigs.GAP_BETWEEN_LEVELS,
) {
    private inner class LayoutNode(
        val tree: T,
        val parent: LayoutNode? = null,
        val depth: Int = 0,
        val childIndex: Int = 0,
    ) {
        // Final coordinates: center X, top Y.
        var x: Double = 0.0
        var y: Double = 0.0

        var prelim: Double = 0.0
        var modifier: Double = 0.0
        var shift: Double = 0.0
        var change: Double = 0.0

        // Threads link contour nodes where a child is absent.
        var thread: LayoutNode? = null
        var ancestor: LayoutNode = this

        val children: MutableList<LayoutNode> = mutableListOf()

        val width: Double = extentProvider.getWidth(tree)
        val height: Double = extentProvider.getHeight(tree)
        var inheritedModifier: Double = 0.0

        val isLeaf: Boolean
            get() = children.isEmpty()

        val hasChildren: Boolean
            get() = children.isNotEmpty()

        val leftSibling: LayoutNode?
            get() = if (childIndex > 0) parent?.children?.get(childIndex - 1) else null

        val leftmostSibling: LayoutNode?
            get() = if (childIndex > 0) parent?.children?.get(0) else null

        val leftmostChild: LayoutNode?
            get() = children.firstOrNull()

        val rightmostChild: LayoutNode?
            get() = children.lastOrNull()
    }

    private val nodeBoundsMap = mutableMapOf<Tree, Rectangle2D.Double>()

    private var cachedBounds: Rectangle2D.Double? = null

    /** Node rectangles; call [layout] before reading. */
    val nodeBounds: Map<Tree, Rectangle2D.Double>
        get() = nodeBoundsMap

    /** Overall bounds; empty until [layout] runs. */
    val bounds: Rectangle2D.Double
        get() = cachedBounds ?: Rectangle2D.Double()

    fun layout(): BuchheimWalkerLayout<T> {
        val nodes = buildLayoutTree()
        firstWalk(nodes.first())
        positionNodes(nodes)
        normalizePositions(nodes)
        nodeBoundsMap.clear()
        for (node in nodes) {
            nodeBoundsMap[node.tree] = Rectangle2D.Double(node.x - node.width / 2.0, node.y, node.width, node.height)
        }
        return this
    }

    /** Breadth-first order keeps parents before children without using the JVM call stack. */
    @Suppress("UNCHECKED_CAST")
    private fun buildLayoutTree(): List<LayoutNode> {
        val nodes = mutableListOf(LayoutNode(root))
        var index = 0
        while (index < nodes.size) {
            val node = nodes[index++]
            for (i in 0 until node.tree.childCount) {
                val child = node.tree.getChild(i) as? T ?: continue
                val childNode = LayoutNode(child, node, node.depth + 1, node.children.size)
                node.children.add(childNode)
                nodes.add(childNode)
            }
        }
        return nodes
    }

    /** Positions children first, resolves contour conflicts, then centers their parent. */
    private fun firstWalk(root: LayoutNode) {
        val pending = ArrayDeque<WalkFrame>()
        pending.addLast(WalkFrame(root))
        while (pending.isNotEmpty()) {
            val frame = pending.last()
            val node = frame.node
            if (frame.nextChild < node.children.size) {
                pending.addLast(WalkFrame(node.children[frame.nextChild++]))
                continue
            }

            if (node.isLeaf) {
                node.prelim = node.leftSibling?.let { it.prelim + spacing(it, node) } ?: 0.0
            } else {
                executeShifts(node)
                val midpoint = (node.leftmostChild!!.prelim + node.rightmostChild!!.prelim) / 2.0
                val sibling = node.leftSibling
                if (sibling == null) {
                    node.prelim = midpoint
                } else {
                    node.prelim = sibling.prelim + spacing(sibling, node)
                    node.modifier = node.prelim - midpoint
                }
            }

            pending.removeLast()
            // Apportion immediately, before laying out the next sibling: its contour may depend
            // on the shifts and threads established by this child.
            pending.lastOrNull()?.let { parent ->
                parent.defaultAncestor = apportion(node, parent.defaultAncestor!!)
            }
        }
    }

    private inner class WalkFrame(val node: LayoutNode) {
        var nextChild = 0
        var defaultAncestor = node.leftmostChild
    }

    /** Walks subtree contours and shifts nodes to resolve horizontal overlaps. */
    private fun apportion(v: LayoutNode, defaultAncestor: LayoutNode): LayoutNode {
        val w = v.leftSibling ?: return defaultAncestor

        var vInnerRight = v
        var vOuterRight = v
        var vInnerLeft = w
        var vOuterLeft = v.leftmostSibling!!

        var sumInnerRight = v.modifier
        var sumOuterRight = v.modifier
        var sumInnerLeft = vInnerLeft.modifier
        var sumOuterLeft = vOuterLeft.modifier

        while (nextRight(vInnerLeft) != null && nextLeft(vInnerRight) != null) {
            vInnerLeft = nextRight(vInnerLeft)!!
            vInnerRight = nextLeft(vInnerRight)!!
            vOuterLeft = nextLeft(vOuterLeft)!!
            vOuterRight = nextRight(vOuterRight)!!

            vOuterRight.ancestor = v

            val shift =
                (vInnerLeft.prelim + sumInnerLeft) - (vInnerRight.prelim + sumInnerRight) +
                    spacing(vInnerLeft, vInnerRight)

            if (shift > 0) {
                moveSubtree(ancestor(vInnerLeft, v, defaultAncestor), v, shift)
                sumInnerRight += shift
                sumOuterRight += shift
            }

            sumInnerLeft += vInnerLeft.modifier
            sumInnerRight += vInnerRight.modifier
            sumOuterLeft += vOuterLeft.modifier
            sumOuterRight += vOuterRight.modifier
        }

        if (nextRight(vInnerLeft) != null && nextRight(vOuterRight) == null) {
            vOuterRight.thread = nextRight(vInnerLeft)
            vOuterRight.modifier += sumInnerLeft - sumOuterRight
        }

        if (nextLeft(vInnerRight) != null && nextLeft(vOuterLeft) == null) {
            vOuterLeft.thread = nextLeft(vInnerRight)
            vOuterLeft.modifier += sumInnerRight - sumOuterLeft
            return v
        }

        return defaultAncestor
    }

    /** Applies the shift now and records its later distribution across intervening siblings. */
    private fun moveSubtree(wMinus: LayoutNode, wPlus: LayoutNode, shift: Double) {
        val subtrees = (wPlus.childIndex - wMinus.childIndex).toDouble()
        if (subtrees > 0) {
            wPlus.change -= shift / subtrees
            wPlus.shift += shift
            wMinus.change += shift / subtrees
        }
        wPlus.prelim += shift
        wPlus.modifier += shift
    }

    /** Accumulates deferred shifts from right to left. */
    private fun executeShifts(v: LayoutNode) {
        var shift = 0.0
        var change = 0.0

        for (i in v.children.indices.reversed()) {
            val w = v.children[i]
            w.prelim += shift
            w.modifier += shift
            change += w.change
            shift += w.shift + change
        }
    }

    private fun ancestor(
        vInnerLeft: LayoutNode,
        v: LayoutNode,
        defaultAncestor: LayoutNode,
    ): LayoutNode =
        if (vInnerLeft.ancestor.parent === v.parent) {
            vInnerLeft.ancestor
        } else {
            defaultAncestor
        }

    private fun nextLeft(v: LayoutNode): LayoutNode? = if (v.hasChildren) v.leftmostChild else v.thread

    private fun nextRight(v: LayoutNode): LayoutNode? = if (v.hasChildren) v.rightmostChild else v.thread

    /** Minimum center-to-center distance; contour traversal handles deeper overlaps. */
    private fun spacing(left: LayoutNode, right: LayoutNode): Double = (left.width + right.width) / 2.0 + horizontalGap

    /** Computes level heights once, then accumulates both vertical offsets and horizontal modifiers. */
    private fun positionNodes(nodes: List<LayoutNode>) {
        val levelHeights = DoubleArray(nodes.last().depth + 1)
        for (node in nodes) {
            levelHeights[node.depth] = maxOf(levelHeights[node.depth], node.height)
        }
        val levelOffsets = DoubleArray(levelHeights.size)
        for (depth in 1 until levelOffsets.size) {
            levelOffsets[depth] = levelOffsets[depth - 1] + levelHeights[depth - 1] + verticalSpacing
        }
        for (node in nodes) {
            node.inheritedModifier = node.parent?.let { it.inheritedModifier + it.modifier } ?: -node.prelim
            node.x = node.prelim + node.inheritedModifier
            node.y = levelOffsets[node.depth]
        }
    }

    /** Shifts negative bounds to zero and computes the overall rectangle. */
    private fun normalizePositions(nodes: List<LayoutNode>) {
        var minX = Double.MAX_VALUE
        var minY = Double.MAX_VALUE
        var maxX = Double.NEGATIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY

        for (node in nodes) {
            val nodeLeft = node.x - node.width / 2
            val nodeRight = node.x + node.width / 2
            val nodeTop = node.y
            val nodeBottom = node.y + node.height

            minX = minOf(minX, nodeLeft)
            minY = minOf(minY, nodeTop)
            maxX = maxOf(maxX, nodeRight)
            maxY = maxOf(maxY, nodeBottom)
        }

        val offsetX = if (minX < 0) -minX else 0.0
        val offsetY = if (minY < 0) -minY else 0.0

        if (offsetX != 0.0 || offsetY != 0.0) {
            for (node in nodes) {
                node.x += offsetX
                node.y += offsetY
            }
            maxX += offsetX
            maxY += offsetY
        }

        cachedBounds = Rectangle2D.Double(0.0, 0.0, maxX, maxY)
    }

    companion object {
        fun <T : Tree> create(
            root: T,
            extentProvider: NodeExtentProvider<T?>,
            horizontalGap: Double = HardcodedConfigs.GAP_BETWEEN_NODES,
            verticalSpacing: Double = HardcodedConfigs.GAP_BETWEEN_LEVELS,
        ): BuchheimWalkerLayout<T> = BuchheimWalkerLayout(root, extentProvider, horizontalGap, verticalSpacing).layout()
    }
}
