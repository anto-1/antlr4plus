package com.antlr4plus.preview.tree

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.Point
import java.awt.image.BufferedImage
import org.antlr.v4.runtime.CommonToken
import org.antlr.v4.runtime.ParserRuleContext

class TreeViewerDeepTreeTest : BasePlatformTestCase() {
    fun `test installing painting and locating a deep tree`() {
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
        repeat(12_000) {
            leaf =
                ParserRuleContext(leaf, -1).also { child ->
                    child.start = token
                    child.stop = token
                    leaf.addChild(child)
                }
        }
        val (viewer, _) = UberTreeViewer.createUberPanel(project, testRootDisposable)
        viewer.treeModel.treeTextProvider = org.antlr.v4.gui.TreeTextProvider { "node" }
        viewer.ignoreEditorUpdate = true
        val treeViewer = viewer.treeViewer
        treeViewer.setTree(root)
        assertEquals(12_001, treeViewer.treeLayout!!.nodeBounds.size)

        val bounds = treeViewer.getBoundsOfNode(leaf)!!
        val top = (bounds.y + treeViewer.viewportPadding - 50).toInt()
        viewer.viewport.setSize(200, 100)
        viewer.viewport.viewPosition = Point(0, top)
        assertNotNull(treeViewer.findCentermostNode(viewer.viewport))
        val image = BufferedImage(200, 100, BufferedImage.TYPE_INT_ARGB)
        for (mode in TreeViewer.ConnectionMode.values()) {
            treeViewer.setConnectionMode(mode)
            val graphics = image.createGraphics()
            try {
                graphics.translate(0, -top)
                graphics.setClip(0, top, 200, 100)
                treeViewer.paintForExport(graphics, fillBackground = false)
            } finally {
                graphics.dispose()
            }
        }
        assertTrue(
            "The viewport should contain painted tree pixels",
            (0 until image.height).any { y ->
                (0 until image.width).any { x -> image.getRGB(x, y) != 0 }
            },
        )
    }
}
