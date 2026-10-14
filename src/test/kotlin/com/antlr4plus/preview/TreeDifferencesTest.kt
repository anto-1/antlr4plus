package com.antlr4plus.preview

import junit.framework.TestCase
import org.antlr.v4.runtime.CommonToken
import org.antlr.v4.runtime.ParserRuleContext
import org.antlr.v4.runtime.tree.TerminalNodeImpl
import org.antlr.v4.runtime.tree.Tree

class TreeDifferencesTest : TestCase() {
    fun `test matching trees do not add differences or remove prior markers`() {
        val marker = rule(99)
        val differences = mutableSetOf<Tree>(marker)
        findDifferingNodes(rule(0, token(1, "x")), rule(0, token(1, "x")), differences)
        assertEquals(setOf(marker), differences)
    }

    fun `test differing rule marks only its entire second subtree`() {
        val changedLeaf = token(1, "x")
        val changedRule = rule(2, changedLeaf)
        val second = rule(0, changedRule, token(2, "y"))
        assertEquals(
            setOf(changedRule, changedLeaf),
            differences(rule(0, rule(1, token(1, "x")), token(2, "y")), second),
        )
    }

    fun `test token type text and node kind all affect comparison`() {
        val first = rule(0, token(1, "x"), token(2, "y"), rule(3))
        val second = rule(0, token(2, "x"), token(2, "z"), token(3, ""))
        assertEquals((0 until second.childCount).map { second.getChild(it) }.toSet(), differences(first, second))
    }

    fun `test missing child highlights its surviving parent`() {
        val first = rule(0, token(1, "x"), token(2, "y"))
        val second = rule(0, token(1, "x"))
        assertEquals(setOf(second), differences(first, second))
    }

    fun `test additional branch highlights parent and all added descendants`() {
        val addedLeaf = token(2, "y")
        val added = rule(3, addedLeaf)
        val first = rule(0, token(1, "x"))
        val second = rule(0, token(1, "x"), added)
        assertEquals(setOf(second, added, addedLeaf), differences(first, second))
    }

    fun `test deep matching and mismatching trees do not overflow`() {
        val first = deepTree(20_000)
        val second = deepTree(20_000)
        assertTrue(differences(first, second).isEmpty())
        assertEquals(20_001, differences(rule(7), second).size)
    }

    private fun deepTree(depth: Int): ParserRuleContext {
        var tree = rule(0)
        repeat(depth) { tree = rule(0, tree) }
        return tree
    }

    private fun differences(first: Tree, second: Tree): Set<Tree> =
        mutableSetOf<Tree>().also { findDifferingNodes(first, second, it) }

    private fun rule(index: Int, vararg children: Tree): ParserRuleContext =
        object : ParserRuleContext() {
                override fun getRuleIndex(): Int = index
            }
            .apply {
                children.forEach {
                    when (it) {
                        is ParserRuleContext -> addChild(it)
                        is TerminalNodeImpl -> addChild(it)
                    }
                }
            }

    private fun token(type: Int, text: String) = TerminalNodeImpl(CommonToken(type, text))
}
