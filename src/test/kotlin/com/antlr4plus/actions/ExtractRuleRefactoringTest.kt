package com.antlr4plus.actions

import com.antlr4plus.dotG4Support.refactor.ExtractRuleSelection
import com.antlr4plus.dotG4Support.refactor.GrammarEditPlan
import com.antlr4plus.dotG4Support.refactor.GrammarTextEdit
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.EditorFactory
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.antlr.v4.runtime.BaseErrorListener
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.RecognitionException
import org.antlr.v4.runtime.Recognizer
import org.antlr.v4.tool.Grammar

class ExtractRuleRefactoringTest : BasePlatformTestCase() {
    fun `test extracting a sequence preserves the next adjacent rule`() {
        val original = "grammar Demo; start : 'a' 'b' EOF;tail : 'z';"
        val result = extract(original, "'a' 'b'", "pair")
        assertTrue(result.contains("start : pair EOF;"))
        assertTrue(result.contains("pair : 'a' 'b' ;"))
        assertTrue(result.endsWith("tail : 'z';"))
        assertSameLanguage(original, result, listOf("ab", "a", "b", "abb", "", "z"))
    }

    fun `test extracting complete alternatives preserves choice and suffix behavior`() {
        val original = "grammar Demo; start : ('a' | 'b' | 'c')+ EOF;"
        val result = extract(original, "'a' | 'b'", "choice")
        assertTrue(result.contains("start : (choice | 'c')+ EOF;"))
        assertSameLanguage(original, result, listOf("a", "b", "c", "abc", "bba", "", "z"))
    }

    fun `test extracting a quantified group at EOF preserves the suffix`() {
        val original = "grammar Demo; start : ('a' 'b')? EOF;"
        val result = extract(original, "('a' 'b')?", "pair")
        assertTrue(result.endsWith("pair : ('a' 'b')? ;\n"))
        assertSameLanguage(original, result, listOf("ab", "", "a", "abab"))
    }

    fun `test Unicode selection and comments retain their document positions`() {
        val original = "grammar Demo; /* 😀 */ start : '🦊' /* inside */ 'x' EOF; // keep\ntail : 'z';"
        val result = extract(original, "'🦊' /* inside */ 'x'", "pair")
        assertTrue(result.contains("/* 😀 */ start : pair EOF; // keep\npair : '🦊' /* inside */ 'x' ;\n"))
        assertTrue(result.endsWith("tail : 'z';"))
        assertSameLanguage(original, result, listOf("🦊x", "🦊", "x"))
    }

    fun `test lexer extraction creates a fragment and leaves commands on the token rule`() {
        val original = "lexer grammar Demo; WORD : [a-z]+ -> skip; NEXT : 'x';"
        val result = extract(original, "[a-z]+", "LETTERS")
        assertTrue(result.contains("WORD : LETTERS -> skip;"))
        assertTrue(result.contains("fragment LETTERS : [a-z]+ ;"))
        assertEquals(0, org.antlr.v4.tool.LexerGrammar(result).tool.numErrors)
    }

    fun `test lexer extraction remains in its original mode`() {
        val original =
            "lexer grammar Demo; OPEN : '<' -> pushMode(INNER); mode INNER; WORD : [a-z]+; mode LAST; CLOSE : '>';"
        val result = extract(original, "[a-z]+", "LETTERS")
        assertTrue(result.indexOf("fragment LETTERS") > result.indexOf("mode INNER"))
        assertTrue(result.indexOf("fragment LETTERS") < result.indexOf("mode LAST"))
        assertEquals(0, org.antlr.v4.tool.LexerGrammar(result).tool.numErrors)
    }

    fun `test partial tokens unbalanced blocks cross alternatives and cross rules are rejected`() {
        val grammar = "grammar Demo; start : 'word' ('a' | 'b')+ 'c' | 'd' 'e'; tail : 'z';"
        for (selected in listOf("word", "'a' |", "('a' | 'b'", "'b')+ 'c'", "'c' | 'd'", "'e'; tail : 'z'")) {
            assertNull(selected, candidate(grammar, selected))
        }
        assertNull(ExtractRuleSelection.find(grammar, -1, 2))
        assertNull(ExtractRuleSelection.find(grammar, 0, grammar.length + 1))
        assertNull(ExtractRuleSelection.find(grammar, 5, 5))
    }

    fun `test extraction refuses to break labels implicit action references and left recursion`() {
        assertNull(candidate("grammar Demo; start : id='x';", "id='x'"))
        assertNull(candidate("grammar Demo; start : ID {System.out.println(${'$'}ID.text);}; ID : 'x';", "ID"))
        assertNull(candidate("grammar Demo; start : item[1]; item[int n] : 'x';", "item[1]"))
        assertNull(candidate("grammar Demo; start : {System.out.println(1);} 'x';", "{System.out.println(1);}"))
        assertNull(candidate("grammar Demo; expr : expr '+' expr | 'x';", "expr '+' expr"))
        assertNull(candidate("grammar Demo; expr : 'x' | expr '+' expr;", "'x' | expr '+' expr"))
    }

    fun `test new names are checked against lexer syntax declarations and references`() {
        val grammar = "grammar Demo; start : 'a' newRule newRule1; newRule : 'b';"
        val selection = candidate(grammar, "'a'")!!
        assertEquals("newRule2", selection.suggestedName)
        for (name in
            listOf("", "BadCase", "with space", "'literal'", "fragment", "start", "newRule1", "x; injected : 'z'")) {
            assertNotNull(name, selection.nameError(name))
            assertNull(name, selection.plan(name))
        }
        assertNull(selection.nameError("élément"))
        val lexer = candidate("lexer grammar Demo; WORD : [a-z]+;", "[a-z]+")!!
        assertEquals("NEW_RULE", lexer.suggestedName)
        assertNotNull(lexer.nameError("lowercase"))
        assertNotNull(lexer.nameError("EOF"))
    }

    fun `test planned edits refuse changed documents without partial writes`() {
        val grammar = "grammar Demo; start : 'a' 'b';"
        val plan = candidate(grammar, "'a' 'b'")!!.plan("pair")!!
        val document = EditorFactory.getInstance().createDocument(grammar + " // changed")
        WriteCommandAction.runWriteCommandAction(project) {
            assertFalse(plan.applyTo(document))
        }
        assertEquals(grammar + " // changed", document.text)
    }

    fun `test malformed characters hidden by the editor lexer prevent extraction`() {
        assertNull(candidate("grammar Demo; start : 'a' % 'b';", "'a' % 'b'"))
    }

    fun `test read-only documents and guarded ranges prevent every edit`() {
        val grammar = "grammar Demo; start : 'a' 'b';"
        val plan = candidate(grammar, "'a' 'b'")!!.plan("pair")!!
        val document = EditorFactory.getInstance().createDocument(grammar)
        WriteCommandAction.runWriteCommandAction(project) {
            document.setReadOnly(true)
            assertFalse(plan.applyTo(document))
            document.setReadOnly(false)
            val guard = document.createGuardedBlock(grammar.indexOf("'a'"), grammar.indexOf("'b'") + 3)
            try {
                assertFalse(plan.applyTo(document))
                assertEquals(grammar, document.text)
            } finally {
                document.removeGuardedBlock(guard)
            }
        }
    }

    fun `test overlapping edit ranges are rejected before any edit is applied`() {
        try {
            GrammarEditPlan("abcdef", listOf(GrammarTextEdit(1, 4, "x"), GrammarTextEdit(3, 5, "y")))
            fail("Expected invalid ranges to be rejected")
        } catch (_: IllegalArgumentException) {}
    }

    private fun candidate(grammar: String, selected: String): ExtractRuleSelection? {
        val start = grammar.indexOf(selected)
        assertTrue("Selection must exist in test input: $selected", start >= 0)
        return ExtractRuleSelection.find(grammar, start, start + selected.length)
    }

    private fun extract(grammar: String, selected: String, name: String): String {
        val selection = candidate(grammar, selected)
        assertNotNull(selected, selection)
        val plan = selection!!.plan(name)
        assertNotNull(selection.nameError(name), plan)
        val document = EditorFactory.getInstance().createDocument(grammar)
        WriteCommandAction.runWriteCommandAction(project) { assertTrue(plan!!.applyTo(document)) }
        assertEquals(plan!!.resultText(), document.text)
        return document.text
    }

    private fun assertSameLanguage(before: String, after: String, inputs: List<String>) {
        fun acceptance(text: String): List<Boolean> {
            val grammar = Grammar(text)
            assertEquals("Grammar must remain semantically valid: $text", 0, grammar.tool.numErrors)
            return inputs.map { input ->
                var errors = 0
                val listener =
                    object : BaseErrorListener() {
                        override fun syntaxError(
                            recognizer: Recognizer<*, *>?,
                            offendingSymbol: Any?,
                            line: Int,
                            charPositionInLine: Int,
                            msg: String?,
                            e: RecognitionException?,
                        ) {
                            errors++
                        }
                    }
                val lexer = grammar.createLexerInterpreter(CharStreams.fromString(input))
                lexer.removeErrorListeners()
                lexer.addErrorListener(listener)
                val parser = grammar.createParserInterpreter(CommonTokenStream(lexer))
                parser.removeErrorListeners()
                parser.addErrorListener(listener)
                parser.parse(grammar.getRule("start").index)
                errors == 0
            }
        }
        assertEquals(acceptance(before), acceptance(after))
    }
}
