package com.antlr4plus.preview.tree

import com.antlr4plus.preview.formatHierarchyText
import com.antlr4plus.preview.hierarchy.wrapHierarchyTree
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.antlr.v4.runtime.CommonToken
import org.antlr.v4.runtime.ParserRuleContext
import org.antlr.v4.runtime.tree.TerminalNodeImpl

class TreeViewerTextExportTest : BasePlatformTestCase() {
    fun `test exported labels escape original whitespace exactly once`() {
        val root =
            object : ParserRuleContext() {
                override fun getRuleIndex(): Int = 0
            }
        listOf("a\nb", "a\\nb", "a b", "a\tb").forEach {
            root.addChild(TerminalNodeImpl(CommonToken(1, it)))
        }
        val (viewer, _) = UberTreeViewer.createUberPanel(project, testRootDisposable)
        viewer.treeModel.setTreeTextProvider(listOf("root"))
        viewer.ignoreEditorUpdate = true
        viewer.treeViewer.setTree(root)
        val expected = "root\n├── <a\\nb>\n├── <a\\\\nb>\n├── <a b>\n└── <a\\tb>\n"
        assertEquals(expected, viewer.generateTreeText())
        assertEquals(
            expected,
            formatHierarchyText(wrapHierarchyTree(root)) { viewer.treeModel.treeTextProvider?.getText(it) },
        )
        // Canvas labels still render whitespace visibly; export reads the original labels.
        assertEquals("a·b", viewer.treeModel.getText(root.getChild(2)))
    }
}
