package com.antlr4plus.preview

import com.antlr4plus.preview.hierarchy.wrapHierarchyTree
import com.antlr4plus.preview.tree.BuchheimWalkerLayout
import com.antlr4plus.preview.tree.CachedExtentProvider
import com.antlr4plus.preview.tree.TreeViewer
import com.antlr4plus.preview.tree.collectSubtreeBounds
import com.antlr4plus.preview.tree.measureTreeNodeExtents
import java.awt.geom.Rectangle2D
import javax.swing.tree.DefaultMutableTreeNode
import junit.framework.TestCase
import org.antlr.v4.runtime.CommonToken
import org.antlr.v4.runtime.ParserRuleContext
import org.antlr.v4.runtime.tree.TerminalNodeImpl
import org.antlr.v4.runtime.tree.Tree

class PreviewTreeTraversalTest : TestCase() {
    fun `test deep tree measurement layout bounds hierarchy and caret lookup`() {
        val token =
            CommonToken(1, "x").apply {
                startIndex = 0
                stopIndex = 0
            }
        val root =
            ParserRuleContext().apply {
                start = token
                stop = token
            }
        var leaf = root
        repeat(20_000) {
            leaf =
                ParserRuleContext(leaf, -1).also { child ->
                    child.start = token
                    child.stop = token
                    leaf.addChild(child)
                }
        }
        val terminal = TerminalNodeImpl(token)
        leaf.addChild(terminal)
        var labelsMeasured = 0
        val metrics = TreeViewer.FontMetricsSnapshot(IntArray(256) { 7 }, 7, 10, 2, 2)
        val extents =
            measureTreeNodeExtents(root, metrics) {
                labelsMeasured++
                "node"
            }
        assertEquals(20_002, labelsMeasured)
        val layout = BuchheimWalkerLayout.create<Tree>(root, CachedExtentProvider(extents))
        val bounds = collectSubtreeBounds(root) { layout.nodeBounds[it] }
        assertEquals(layout.bounds, bounds.getValue(root))
        assertEquals(layout.nodeBounds.getValue(terminal), bounds.getValue(terminal))
        assertSame(terminal, findTreeNodeAtOffset(root, 0))
        assertNull(findTreeNodeAtOffset(root, 1))
        val hierarchy = wrapHierarchyTree(root)!!
        assertSame(terminal, findHierarchyNodeAtOffset(hierarchy, 0)!!.userObject)
    }

    fun `test subtree bounds skip absent branches and leave input rectangles unchanged`() {
        val root = ParserRuleContext()
        val included = ParserRuleContext(root, -1).also { root.addChild(it) }
        val excluded = ParserRuleContext(root, -1).also { root.addChild(it) }
        val skipped = ParserRuleContext(excluded, -1).also { excluded.addChild(it) }
        val rootBounds = Rectangle2D.Double(10.0, 10.0, 20.0, 10.0)
        val includedBounds = Rectangle2D.Double(-5.0, 30.0, 10.0, 20.0)
        val bounds =
            collectSubtreeBounds(root) {
                when (it) {
                    root -> rootBounds
                    included -> includedBounds
                    skipped -> fail("Descendants of an absent branch must be skipped").let { null }
                    else -> null
                }
            }
        assertEquals(2, bounds.size)
        assertEquals(Rectangle2D.Double(-5.0, 10.0, 35.0, 40.0), bounds[root])
        assertEquals(Rectangle2D.Double(10.0, 10.0, 20.0, 10.0), rootBounds)
        assertEquals(includedBounds, bounds[included])
        assertNotSame(includedBounds, bounds[included])
    }

    fun `test caret lookup keeps first matching branch and falls back to enclosing rule`() {
        val root = rule(0, 10)
        val first = rule(2, 4).also { root.addChild(it) }
        val second = rule(2, 7).also { root.addChild(it) }
        assertSame(first, findTreeNodeAtOffset(root, 3))
        assertSame(second, findTreeNodeAtOffset(root, 6))
        assertSame(root, findTreeNodeAtOffset(root, 9))
        assertNull(findTreeNodeAtOffset(root, -1))
        val hierarchy = wrapHierarchyTree(root)!!
        assertSame(first, findHierarchyNodeAtOffset(hierarchy, 3)!!.userObject)
        assertSame(second, findHierarchyNodeAtOffset(hierarchy, 6)!!.userObject)
        assertSame(root, findHierarchyNodeAtOffset(hierarchy, 9)!!.userObject)
        assertNull(findHierarchyNodeAtOffset(DefaultMutableTreeNode("Loading"), 0))
    }

    private fun rule(from: Int, to: Int) =
        ParserRuleContext().apply {
            start = CommonToken(1).apply { startIndex = from }
            stop = CommonToken(1).apply { stopIndex = to }
        }
}
