package com.antlr4plus.parsing

import com.antlr4plus.preview.findHierarchyNodeAtOffset
import com.antlr4plus.preview.findTreeNodeAtOffset
import com.antlr4plus.preview.getHierarchySelectionOffset
import com.antlr4plus.preview.getTreeSelectionOffsets
import com.antlr4plus.preview.hierarchy.wrapHierarchyTree
import javax.swing.tree.TreePath
import junit.framework.TestCase
import org.antlr.v4.runtime.ANTLRInputStream
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonToken
import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.TokenSource
import org.antlr.v4.runtime.misc.Pair as AntlrPair
import org.antlr.v4.tool.Grammar

class InputTextCoordinatesTest : TestCase() {
    fun `test code point ranges include complete supplementary characters and empty EOF`() {
        val positions = InputTextCoordinates("a😀b𐐨")
        assertEquals(listOf(0, 1, 3, 4, 6), (0..4).map { positions.toEditor(it) })
        assertEquals(1 to 2, positions.inclusiveRange(1, 1))
        assertEquals(4 to 5, positions.inclusiveRange(3, 3))
        assertEquals(6 to 5, positions.inclusiveRange(4, 3))
        assertEquals(listOf(0, 1, 1, 2, 3, 3, 4), (0..6).map { positions.toNative(it) })
        assertNull(positions.toNative(-1))
        assertNull(positions.toNative(7))
        assertNull(positions.toEditor(-1))
        assertNull(positions.toEditor(5))
        assertNull(positions.inclusiveRange(0, Int.MAX_VALUE))
        assertNull(positions.inclusiveRange(2, 0))
        assertEquals(0 to -1, InputTextCoordinates("").inclusiveRange(0, -1))
    }

    fun `test line and column conversion handles CRLF trailing newline and invalid coordinates`() {
        val positions = InputTextCoordinates("😀x\r\n𐐨z\n")
        assertEquals(2, positions.lineColumnOffset(1, 1))
        assertEquals(7, positions.lineColumnOffset(2, 1))
        assertEquals(9, positions.lineColumnOffset(3, 0))
        assertNull(positions.lineColumnOffset(0, 0))
        assertNull(positions.lineColumnOffset(4, 0))
        assertNull(positions.lineColumnOffset(1, -1))
        assertNull(positions.lineColumnOffset(1, 20))
    }

    fun `test parsed token lookup and both tree selections use UTF-16 without mutating tokens`() {
        val grammar = Grammar("grammar Unicode; start: UNIT* EOF; UNIT:.;")
        val input = CharStreams.fromString("😀x𐐨y")
        val tokens = CommonTokenStream(grammar.createLexerInterpreter(input))
        val parser = grammar.createParserInterpreter(tokens)
        val tree = parser.parse(grammar.getRule("start").index)
        val result = ParsingResult.java(parser, tree, null)
        val hierarchy = wrapHierarchyTree(tree)!!
        val expected = listOf(0, 0, 1, 2, 2, 3)
        expected.forEachIndexed { offset, child ->
            val terminal = tree.getChild(child)
            assertSame(terminal, result.findTerminalAtOffset(offset))
            assertSame(terminal, findTreeNodeAtOffset(tree, offset))
            val node = findHierarchyNodeAtOffset(hierarchy, offset)!!
            assertSame(terminal, node.userObject)
            assertEquals(getTreeSelectionOffsets(terminal)!!.first, getHierarchySelectionOffset(TreePath(node.path)))
        }
        assertEquals(3 to 4, getTreeSelectionOffsets(tree.getChild(2)))
        assertEquals(3, result.getTokenFromTerminal(tree.getChild(2))!!.startIndex)
        assertEquals(4, result.getTokenFromTerminal(tree.getChild(2))!!.stopIndex)
        assertEquals(0 to 5, result.getRuleCharacterRange(tree))
        assertNull(result.findTerminalAtOffset(6))
        assertEquals(listOf(0, 1, 2, 3, 4), tokens.tokens.map { it.startIndex })
        input.seek(0)
        assertEquals(3 to 4, getTreeSelectionOffsets(tree.getChild(2)))
        assertEquals(0, input.index())
    }

    fun `test grammar token lookup converts editor offsets after supplementary comments`() {
        val grammar = "grammar Unicode; /* 😀 */ start:'x';"
        val tokens = ParsingUtils.tokenizeANTLRGrammar(grammar)
        val name = grammar.indexOf("start")
        assertEquals("start", ParsingUtils.getTokenUnderCursor(tokens, name)!!.text)
        assertEquals("start", ParsingUtils.getTokenUnderCursor(tokens, name + 4)!!.text)
        assertNull(ParsingUtils.getTokenUnderCursor(tokens, grammar.length))
        assertNull(ParsingUtils.getTokenUnderCursor(tokens, -1))
    }

    @Suppress("DEPRECATION")
    fun `test legacy UTF-16 streams are not converted twice`() {
        val input = ANTLRInputStream("😀x")
        val token = CommonToken(AntlrPair<TokenSource?, org.antlr.v4.runtime.CharStream>(null, input), 1, 0, 2, 2)
        assertEquals(2 to 2, editorTokenRange(token))
    }
}
