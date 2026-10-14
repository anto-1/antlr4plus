package com.antlr4plus.parsing

import com.antlr4plus.parsing.kotlin.KotlinTreeAdapter
import junit.framework.TestCase
import org.antlr.v4.runtime.CommonToken
import org.antlr.v4.runtime.ParserRuleContext
import org.antlr.v4.runtime.tree.TerminalNodeImpl

class ParseTreeTokensTest : TestCase() {
    fun `test Java terminal lookup handles deep recovered trees with no parent ranges`() {
        val root = ParserRuleContext()
        var leaf = root
        repeat(20_000) {
            leaf = ParserRuleContext(leaf, -1).also { leaf.addChild(it) }
        }
        val terminal = terminal(2, 4)
        leaf.addChild(terminal)
        for (offset in 2..4) assertSame(terminal, findTerminalNodeAtOffset(root, offset))
        assertNull(findTerminalNodeAtOffset(root, 1))
        assertNull(findTerminalNodeAtOffset(root, 5))
    }

    fun `test terminal lookup keeps the first matching branch in source order`() {
        val root = ParserRuleContext()
        val branch = ParserRuleContext(root, -1).also { root.addChild(it) }
        val first = terminal(2, 4).also { branch.addChild(it) }
        val overlapping = terminal(2, 6).also { root.addChild(it) }
        val last = terminal(10, 12).also { root.addChild(it) }
        assertSame(first, findTerminalNodeAtOffset(root, 3))
        assertSame(overlapping, findTerminalNodeAtOffset(root, 5))
        assertSame(last, findTerminalNodeAtOffset(root, 12))
        assertNull(findTerminalNodeAtOffset(root, 8))
    }

    fun `test terminal roots and empty token ranges retain inclusive boundaries`() {
        val root = terminal(2, 4)
        for (offset in 2..4) assertSame(root, findTerminalNodeAtOffset(root, offset))
        assertNull(findTerminalNodeAtOffset(root, 1))
        assertNull(findTerminalNodeAtOffset(root, 5))
        assertNull(findTerminalNodeAtOffset(terminal(5, 4), 5))
        assertNull(findTerminalNodeAtOffset(ParserRuleContext(), 0))
    }

    fun `test Kotlin terminal lookup traverses deeply nested runtime adapters`() {
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
        val addChild = contextClass.getMethod("addChild", ruleClass)
        val root = constructor.newInstance()
        var leaf = root
        repeat(5_000) {
            val child = constructor.newInstance()
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
        for (offset in 2..4) assertEquals(expected, findTerminalNodeAtOffset(tree, offset))
        assertNull(findTerminalNodeAtOffset(tree, 1))
        assertNull(findTerminalNodeAtOffset(tree, 5))
    }

    private fun terminal(start: Int, stop: Int) =
        TerminalNodeImpl(
            CommonToken(1, "x").apply {
                startIndex = start
                stopIndex = stop
            },
        )
}
