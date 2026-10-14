package com.antlr4plus.preview

import com.antlr4plus.parsing.kotlin.KotlinTreeAdapter
import com.antlr4plus.preview.hierarchy.wrapHierarchyTree
import com.antlr4plus.preview.tree.BuchheimWalkerLayout
import com.antlr4plus.preview.tree.CachedExtentProvider
import com.antlr4plus.preview.tree.TreeViewer
import com.antlr4plus.preview.tree.collectSubtreeBounds
import com.antlr4plus.preview.tree.measureTreeNodeExtents
import junit.framework.TestCase
import org.antlr.v4.runtime.tree.Tree

class KotlinPreviewTreeTraversalTest : TestCase() {
    fun `test Kotlin runtime adapters retain identity across deep preview traversals`() {
        // The Kotlin runtime is isolated and is deliberately not on the compile classpath.
        val loader = javaClass.classLoader
        val contextClass = loader.loadClass("org.antlr.v4.kotlinruntime.ParserRuleContext")
        val ruleClass = loader.loadClass("org.antlr.v4.kotlinruntime.RuleContext")
        val tokenClass = loader.loadClass("org.antlr.v4.kotlinruntime.Token")
        val commonTokenClass = loader.loadClass("org.antlr.v4.kotlinruntime.CommonToken")
        val terminalClass = loader.loadClass("org.antlr.v4.kotlinruntime.tree.TerminalNode")
        val token = commonTokenClass.getConstructor(Int::class.javaPrimitiveType).newInstance(1)
        commonTokenClass.getMethod("setStartIndex", Int::class.javaPrimitiveType).invoke(token, 2)
        commonTokenClass.getMethod("setStopIndex", Int::class.javaPrimitiveType).invoke(token, 4)
        val constructor = contextClass.getConstructor()
        val setStart = contextClass.getMethod("setStart", tokenClass)
        val setStop = contextClass.getMethod("setStop", tokenClass)
        val addChild = contextClass.getMethod("addChild", ruleClass)

        fun context(): Any =
            constructor.newInstance().also {
                setStart.invoke(it, token)
                setStop.invoke(it, token)
            }

        val root = context()
        var leaf = root
        repeat(5_000) {
            val child = context()
            addChild.invoke(leaf, child)
            leaf = child
        }
        val terminal =
            loader
                .loadClass("org.antlr.v4.kotlinruntime.tree.TerminalNodeImpl")
                .getConstructor(tokenClass)
                .newInstance(token)
        contextClass.getMethod("addChild", terminalClass).invoke(leaf, terminal)
        val tree = KotlinTreeAdapter.wrap(root, loader)!!
        val expected = KotlinTreeAdapter.wrap(terminal, loader)!!
        val metrics = TreeViewer.FontMetricsSnapshot(IntArray(256) { 7 }, 7, 10, 2, 2)
        val extents = measureTreeNodeExtents(tree, metrics) { "node" }
        val layout = BuchheimWalkerLayout.create<Tree>(tree, CachedExtentProvider(extents))
        val bounds = collectSubtreeBounds(tree) { layout.nodeBounds[it] }
        assertEquals(5_002, bounds.size)
        assertEquals(layout.bounds, bounds[tree])
        assertEquals(layout.nodeBounds[expected], bounds[expected])
        val hierarchy = wrapHierarchyTree(tree)!!
        for (offset in 2..4) {
            assertEquals(expected, findTreeNodeAtOffset(tree, offset))
            assertEquals(expected, findHierarchyNodeAtOffset(hierarchy, offset)!!.userObject)
        }
        assertNull(findTreeNodeAtOffset(tree, 5))
    }
}
