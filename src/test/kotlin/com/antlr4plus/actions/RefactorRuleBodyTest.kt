package com.antlr4plus.actions

import com.antlr4plus.dotG4Support.refactor.RefactorUtils
import com.antlr4plus.parsing.ParsingUtils.parseANTLRGrammar
import junit.framework.TestCase
import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.ParserRuleContext
import org.antlr.v4.runtime.tree.ParseTree

class RefactorRuleBodyTest : TestCase() {
    fun `test body ends at the semicolon before catch and finally`() {
        val parsed = parseANTLRGrammar("grammar Demo; item : 'x'; catch [Exception e] {} finally {}")
        val definition =
            RefactorUtils.getRuleDefNameNode(parsed.parser, parsed.tree as ParseTree, "item")!!.parent
                as ParserRuleContext
        assertEquals("'x'", RefactorUtils.getRuleText(parsed.parser!!.tokenStream as CommonTokenStream, definition))
    }
}
