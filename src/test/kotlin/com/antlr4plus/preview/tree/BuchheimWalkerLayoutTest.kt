package com.antlr4plus.preview.tree

import junit.framework.TestCase
import org.abego.treelayout.NodeExtentProvider
import org.antlr.v4.runtime.ParserRuleContext
import org.antlr.v4.runtime.tree.Tree

class BuchheimWalkerLayoutTest : TestCase() {
    fun `test levels are separated by the tallest node on the preceding level`() {
        val root = ParserRuleContext()
        val child = root.addRule()
        val leaf = child.addRule()
        val heights = mapOf<Tree, Double>(root to 100.0, child to 10.0, leaf to 70.0)
        val layout = BuchheimWalkerLayout.create<Tree>(root, extents(heights), verticalSpacing = 5.0)

        assertEquals(0.0, layout.nodeBounds.getValue(root).y)
        assertEquals(105.0, layout.nodeBounds.getValue(child).y)
        assertEquals(120.0, layout.nodeBounds.getValue(leaf).y)
        assertEquals(190.0, layout.bounds.height)
    }

    fun `test each node is measured once even in a deep tree`() {
        val nodes = chain(200)
        var widthCalls = 0
        var heightCalls = 0
        val provider =
            object : NodeExtentProvider<Tree?> {
                override fun getWidth(node: Tree?): Double = 20.0.also { widthCalls++ }

                override fun getHeight(node: Tree?): Double = 10.0.also { heightCalls++ }
            }
        BuchheimWalkerLayout.create<Tree>(nodes.first(), provider)
        assertEquals(nodes.size, widthCalls)
        assertEquals(nodes.size, heightCalls)
    }

    fun `test deep trees do not consume the call stack`() {
        val nodes = chain(20_000)
        val layout = BuchheimWalkerLayout.create<Tree>(nodes.first(), extents(), verticalSpacing = 5.0)
        assertEquals(nodes.size, layout.nodeBounds.size)
        assertEquals(15.0 * (nodes.size - 1), layout.nodeBounds.getValue(nodes.last()).y)
    }

    fun `test relayout discards removed nodes and remeasures heights`() {
        val root = ParserRuleContext()
        val removed = root.addRule()
        val heights = mutableMapOf<Tree, Double>(root to 10.0)
        val layout = BuchheimWalkerLayout.create<Tree>(root, extents(heights), verticalSpacing = 5.0)
        root.removeLastChild()
        val replacement = root.addRule()
        heights[root] = 80.0
        layout.layout()

        assertFalse(layout.nodeBounds.containsKey(removed))
        assertEquals(2, layout.nodeBounds.size)
        assertEquals(85.0, layout.nodeBounds.getValue(replacement).y)
    }

    fun `test variable width branches remain ordered and do not overlap`() {
        val random = java.util.Random(713)
        repeat(30) {
            val root = ParserRuleContext()
            val nodes = mutableListOf(root)
            val depths = mutableMapOf(root to 0)
            repeat(200) {
                val parent = nodes[random.nextInt(nodes.size)]
                val child = parent.addRule()
                nodes.add(child)
                depths[child] = depths.getValue(parent) + 1
            }
            val widths: Map<Tree?, Double> = nodes.associateWith { 1.0 + random.nextInt(200) }
            val provider =
                object : NodeExtentProvider<Tree?> {
                    override fun getWidth(node: Tree?): Double = widths.getValue(node)

                    override fun getHeight(node: Tree?): Double = 10.0
                }
            val layout = BuchheimWalkerLayout.create<Tree>(root, provider, horizontalGap = 7.0)
            for (level in nodes.groupBy { depths.getValue(it) }.values) {
                val bounds = level.map { layout.nodeBounds.getValue(it) }.sortedBy { it.x }
                bounds.zipWithNext().forEach { (left, right) ->
                    assertTrue("Overlapping nodes: $left and $right", right.x >= left.maxX + 7.0 - 1e-9)
                }
            }
            nodes
                .filter { it.childCount > 0 }
                .forEach { parent ->
                    val first = layout.nodeBounds.getValue(parent.getChild(0))
                    val last = layout.nodeBounds.getValue(parent.getChild(parent.childCount - 1))
                    assertEquals((first.centerX + last.centerX) / 2, layout.nodeBounds.getValue(parent).centerX, 1e-9)
                }
        }
    }

    private fun ParserRuleContext.addRule(): ParserRuleContext = ParserRuleContext(this, -1).also { addChild(it) }

    private fun chain(size: Int): List<ParserRuleContext> {
        val nodes = mutableListOf(ParserRuleContext())
        repeat(size - 1) { nodes.add(nodes.last().addRule()) }
        return nodes
    }

    private fun extents(heights: Map<Tree, Double> = emptyMap()) =
        object : NodeExtentProvider<Tree?> {
            override fun getWidth(node: Tree?): Double = 20.0

            override fun getHeight(node: Tree?): Double = heights[node] ?: 10.0
        }
}
