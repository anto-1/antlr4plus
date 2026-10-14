package com.antlr4plus.actions

import com.antlr4plus.dotG4Support.refactor.DuplicateRuleResult
import com.antlr4plus.dotG4Support.refactor.duplicateRule
import com.antlr4plus.parsing.ParsingUtils.parseANTLRGrammar
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.EditorFactory
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class UniquifyRuleRefsTest : BasePlatformTestCase() {
    fun `test copies retain arguments returns locals actions and exception handlers`() {
        val definition =
            """
            |item[int n] returns [int value] locals [int seen]
            |@init { ${'$'}seen = ${'$'}n; }
            |: 'x' { ${'$'}value = ${'$'}seen; } ;
            |catch [Exception e] { throw e; }
            |finally { System.out.println("done"); }
            """
                .trimMargin()
        val result = uniquify("grammar Demo; start : item[1] item[2]; $definition", "item")
        assertTrue(result.contains("start : item1[1] item2[2];"))
        assertTrue(result.contains(definition.replaceFirst("item[", "item1[")))
        assertTrue(result.contains(definition.replaceFirst("item[", "item2[")))
        assertSyntaxValid(result)
    }

    fun `test generated names avoid existing definitions and unresolved references`() {
        val result = uniquify("grammar Demo; start : item item item2; item : 'x'; item1 : 'y';", "item")
        assertTrue(result.contains("start : item3 item4 item2;"))
        assertTrue(result.contains("item1 : 'y';"))
        assertSyntaxValid(result)
    }

    fun `test Unicode offsets preserve the original grammar and repeated literals`() {
        val result = uniquify("grammar Demo; /* 😀 */ start : item item; item : '🦊';", "item")
        assertTrue(result.contains("/* 😀 */ start : item1 item2;"))
        assertTrue(result.contains("item1 : '🦊';"))
        assertTrue(result.contains("item2 : '🦊';"))
        assertSyntaxValid(result)
    }

    fun `test fragment copies remain fragments`() {
        val result = uniquify("lexer grammar Demo; PAIR : ITEM ITEM; fragment ITEM : 'x';", "ITEM")
        assertTrue(result.contains("PAIR : ITEM1 ITEM2;"))
        assertTrue(result.contains("fragment ITEM1 : 'x';"))
        assertTrue(result.contains("fragment ITEM2 : 'x';"))
        assertSyntaxValid(result)
    }

    fun `test lexer token producers remain available and their copies are fragments`() {
        val result = uniquify("grammar Demo; start : ITEM EOF; PAIR : ITEM ITEM; ITEM : 'x';", "ITEM")
        assertTrue(result.contains("start : ITEM EOF;"))
        assertTrue(result.contains("ITEM : 'x';"))
        assertTrue(result.contains("fragment ITEM1 : 'x';"))
        assertTrue(result.contains("fragment ITEM2 : 'x';"))
        assertSyntaxValid(result)
    }

    fun `test recursive references stay inside their own copy`() {
        val result = uniquify("grammar Demo; start : item item; item : 'x' item?;", "item")
        assertTrue(result.contains("start : item1 item2;"))
        assertTrue(result.contains("item1 : 'x' item1?;"))
        assertTrue(result.contains("item2 : 'x' item2?;"))
        assertFalse(result.contains("item3"))
        assertSyntaxValid(result)
    }

    fun `test alternative labels are unique across the copied rules`() {
        val result = uniquify("grammar Demo; start : item item; item : 'x' #Letter | 'y' #Other;", "item")
        assertTrue(result.contains("#Letter_item1"))
        assertTrue(result.contains("#Letter_item2"))
        assertEquals(0, org.antlr.v4.tool.Grammar(result).tool.numErrors)
    }

    fun `test alternatives sharing a label still share it within each copy`() {
        val result = uniquify("grammar Demo; start : item item; item : 'x' #Value | 'y' #Value;", "item")
        val grammar = org.antlr.v4.tool.Grammar(result)
        assertEquals(0, grammar.tool.numErrors)
        assertEquals(setOf("Value_item1"), grammar.getRule("item1").altLabels.keys)
        assertEquals(setOf("Value_item2"), grammar.getRule("item2").altLabels.keys)
    }

    fun `test self attributes inside assignment expressions follow the copy name`() {
        val result =
            uniquify(
                "grammar Demo; start : item item; item returns [String value] : 'x' item? { ${'$'}value = ${'$'}item.text; };",
                "item",
            )
        assertTrue(result.contains("${'$'}value = ${'$'}item1.text;"))
        assertTrue(result.contains("${'$'}value = ${'$'}item2.text;"))
        assertFalse(result.contains("${'$'}item.text"))
        assertEquals(0, org.antlr.v4.tool.Grammar(result).tool.numErrors)
    }

    fun `test references in comments and escaped dollars are preserved`() {
        val result =
            uniquify(
                "grammar Demo; start : item item; item : 'x' { /* ${'$'}item.text */ println(\"\\${'$'}item\"); };",
                "item",
            )
        assertTrue(result.contains("/* ${'$'}item.text */"))
        assertTrue(result.contains("\\${'$'}item"))
        assertFalse(result.contains("${'$'}item1"))
    }

    fun `test ambiguous caller action references are rejected`() {
        val grammar = "grammar Demo; start : item item { println(${'$'}item.text); }; item : 'x';"
        val result = duplicateRule(grammar, "item")
        assertTrue(result is DuplicateRuleResult.Rejected)
        assertTrue((result as DuplicateRuleResult.Rejected).reason.contains("explicit labels"))
    }

    fun `test only recursive and single external references do not trigger duplication`() {
        for (grammar in listOf("grammar Demo; item : 'x' item?;", "grammar Demo; start : item; item : 'x' item?;")) {
            assertTrue(duplicateRule(grammar, "item") is DuplicateRuleResult.Rejected)
        }
    }

    fun `test token rules with commands cannot silently turn into fragments`() {
        val grammar = "lexer grammar Demo; BOTH : ITEM ITEM; ITEM : 'x' -> skip;"
        assertTrue(duplicateRule(grammar, "ITEM") is DuplicateRuleResult.Rejected)
    }

    private fun uniquify(grammar: String, name: String): String {
        val result = duplicateRule(grammar, name)
        assertTrue(result.toString(), result is DuplicateRuleResult.Ready)
        val document = EditorFactory.getInstance().createDocument(grammar)
        WriteCommandAction.runWriteCommandAction(project) {
            assertTrue((result as DuplicateRuleResult.Ready).plan.applyTo(document))
        }
        return document.text
    }

    private fun assertSyntaxValid(grammar: String) {
        val errors = parseANTLRGrammar(grammar).syntaxErrorListener!!.syntaxErrors
        assertTrue(errors.toString(), errors.isEmpty())
    }
}
