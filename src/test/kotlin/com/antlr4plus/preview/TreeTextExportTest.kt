package com.antlr4plus.preview

import com.antlr4plus.parsing.kotlin.KotlinTreeAdapter
import com.antlr4plus.preview.hierarchy.wrapHierarchyTree
import java.util.IdentityHashMap
import javax.swing.tree.DefaultMutableTreeNode
import junit.framework.TestCase
import org.antlr.v4.runtime.CommonToken
import org.antlr.v4.runtime.ParserRuleContext
import org.antlr.v4.runtime.tree.TerminalNode
import org.antlr.v4.runtime.tree.TerminalNodeImpl
import org.antlr.v4.runtime.tree.Tree

class TreeTextExportTest : TestCase() {
    fun `test both exports preserve branches terminal markers and escaped whitespace`() {
        val labels = IdentityHashMap<Tree, String>()
        val root = ParserRuleContext().also { labels[it] = "root" }
        val first =
            ParserRuleContext(root, -1).also {
                root.addChild(it)
                labels[it] = "first"
            }
        first.addChild(TerminalNodeImpl(CommonToken(1, "a\n\r\t\\b")))
        root.addChild(TerminalNodeImpl(CommonToken(2, "middle")))
        val last =
            ParserRuleContext(root, -1).also {
                root.addChild(it)
                labels[it] = "last"
            }
        last.addChild(TerminalNodeImpl(CommonToken(3, "z")))
        val text: (Tree) -> String = { labels[it] ?: (it as TerminalNode).text }
        val expected = "root\n├── first\n│   └── <a\\n\\r\\t\\\\b>\n├── <middle>\n└── last\n    └── <z>\n"
        assertEquals(expected, formatTreeText(root, text))
        assertEquals(expected, formatHierarchyText(wrapHierarchyTree(root), text))
    }

    fun `test empty and terminal roots`() {
        assertEquals(
            "",
            formatTreeText(null) {
                fail("No tree")
                ""
            },
        )
        assertEquals(
            "",
            formatHierarchyText(null) {
                fail("No tree")
                ""
            },
        )
        assertEquals(
            "",
            formatHierarchyText(DefaultMutableTreeNode("Loading")) {
                fail("No tree")
                ""
            },
        )
        val leaf = TerminalNodeImpl(CommonToken(1, "x"))
        assertEquals("<x>\n", formatTreeText(leaf) { "x" })
        assertEquals("<x>\n", formatHierarchyText(DefaultMutableTreeNode(leaf)) { null })
    }

    fun `test Kotlin terminal roots retain their terminal markers`() {
        val loader = javaClass.classLoader
        val tokenClass = loader.loadClass("org.antlr.v4.kotlinruntime.Token")
        val token =
            loader
                .loadClass("org.antlr.v4.kotlinruntime.CommonToken")
                .getConstructor(Int::class.javaPrimitiveType)
                .newInstance(1)
        val terminal =
            loader
                .loadClass("org.antlr.v4.kotlinruntime.tree.TerminalNodeImpl")
                .getConstructor(tokenClass)
                .newInstance(token)
        val tree = KotlinTreeAdapter.wrap(terminal, loader)!!
        assertEquals("<x>\n", formatTreeText(tree) { "x" })
        assertEquals("<x>\n", formatHierarchyText(DefaultMutableTreeNode(tree)) { "x" })
    }

    fun `test hierarchy retains placeholder children`() {
        val root = DefaultMutableTreeNode(ParserRuleContext())
        root.add(DefaultMutableTreeNode("Loading\nchildren"))
        root.add(DefaultMutableTreeNode())
        assertEquals("root\n├── Loading\\nchildren\n└── null\n", formatHierarchyText(root) { "root" })
    }

    fun `test deep parse tree exports every node without recursive stack growth`() {
        verifyDeepExport(false)
    }

    fun `test deep hierarchy exports every node without recursive stack growth`() {
        verifyDeepExport(true)
    }

    private fun verifyDeepExport(hierarchy: Boolean) {
        val depth = 5_000
        val root = ParserRuleContext()
        var leaf = root
        repeat(depth) {
            leaf = ParserRuleContext(leaf, -1).also { leaf.addChild(it) }
        }
        leaf.addChild(TerminalNodeImpl(CommonToken(1, "x")))
        val text: (Tree) -> String = { if (it is TerminalNode) it.text else "r" }
        val result = if (hierarchy) formatHierarchyText(wrapHierarchyTree(root), text) else formatTreeText(root, text)
        assertEquals(depth + 2, result.count { it == '\n' })
        assertEquals(2 * depth * depth + 8 * depth + 10, result.length)
        assertTrue(result.endsWith("    ".repeat(depth) + "└── <x>\n"))
    }
}
