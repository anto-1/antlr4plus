package com.antlr4plus.actions

import com.antlr4plus.dotG4Support.refactor.InlineRuleResult
import com.antlr4plus.dotG4Support.refactor.inlineRule
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.EditorFactory
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.antlr.v4.runtime.BaseErrorListener
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.RecognitionException
import org.antlr.v4.runtime.Recognizer
import org.antlr.v4.tool.Grammar

class InlineRuleActionTest : BasePlatformTestCase() {
    fun `test recursive definition survives while external references are inlined`() {
        val grammar = "grammar Demo; start : item EOF; item : 'x' item?;"
        assertEquals("grammar Demo; start : 'x' item? EOF; item : 'x' item?;", inline(grammar, "item"))
    }

    fun `test a rule with only self references is unchanged`() {
        val grammar = "grammar Demo; item : 'x' item?;"
        assertEquals(grammar, inline(grammar, "item"))
    }

    fun `test suffix after comments applies to the entire replacement`() {
        assertEquals(
            "grammar Demo; start : ('x' 'y') /* repeated */ + EOF; ",
            inline("grammar Demo; start : item /* repeated */ + EOF; item : 'x' 'y';", "item"),
        )
    }

    fun `test a body starting with a group is still grouped before a suffix`() {
        assertEquals(
            "grammar Demo; start : (('x' | 'y') 'z')? EOF; ",
            inline("grammar Demo; start : item? EOF; item : ('x' | 'y') 'z';", "item"),
        )
    }

    fun `test replacements on both sides of the definition preserve offsets and comments`() {
        assertEquals(
            "grammar Demo; first : ('x' | 'y'); // keep\nlast : ('x' | 'y') ('x' | 'y');",
            inline("grammar Demo; first : item; item : 'x' | 'y'; // keep\nlast : item item;", "item"),
        )
    }

    fun `test supplementary Unicode characters do not shift document edits`() {
        assertEquals(
            "grammar Demo; /* 😀 */ first : '🦊' '🦊'; ",
            inline("grammar Demo; /* 😀 */ first : item item; item : '🦊';", "item"),
        )
    }

    fun `test labels with the same spelling are not replaced`() {
        assertEquals(
            "grammar Demo; start : item='x' 'y' EOF; ",
            inline("grammar Demo; start : item='x' item EOF; item : 'y';", "item"),
        )
    }

    fun `test inlining a lexer rule retains the rule that produces parser tokens`() {
        assertEquals(
            "grammar Demo; start : ITEM EOF; PAIR : 'x' 'x'; ITEM : 'x';",
            inline("grammar Demo; start : ITEM EOF; PAIR : ITEM ITEM; ITEM : 'x';", "ITEM"),
        )
    }

    fun `test lexer commands with the same spelling are not replaced`() {
        assertEquals(
            "lexer grammar Demo; PAIR : 'x' -> type(ITEM); ITEM : 'x';",
            inline("lexer grammar Demo; PAIR : ITEM -> type(ITEM); ITEM : 'x';", "ITEM"),
        )
    }

    fun `test unused fragment definition is removed after inlining`() {
        assertEquals(
            "lexer grammar Demo; PAIR : ('x' 'y') /* repeat */ +; ",
            inline("lexer grammar Demo; PAIR : ITEM /* repeat */ +; fragment ITEM : 'x' 'y';", "ITEM"),
        )
    }

    fun `test rule arguments returns locals and exception declarations are preserved`() {
        val grammars =
            listOf(
                "grammar Demo; start : item[1] EOF; item[int n] : 'x';",
                "grammar Demo; start : item EOF; item returns [int n] : 'x';",
                "grammar Demo; start : item EOF; item locals [int n] : 'x';",
                "grammar Demo; start : item EOF; item throws Exception : 'x';",
                "grammar Demo; start : item EOF; item @init {prepare();} : 'x';",
                "grammar Demo; start : item EOF; item : 'x'; catch [Exception e] {recover(e);}",
                "grammar Demo; start : item EOF; item : 'x'; finally {finish();}",
            )
        for (grammar in grammars) assertEquals(grammar, grammar, inline(grammar, "item"))
    }

    fun `test body actions predicates labels and alternative labels keep their scope`() {
        val grammars =
            listOf(
                "grammar Demo; start : item EOF; item : 'x' {use(${'$'}text);};",
                "grammar Demo; start : item EOF; item : {allowed()}? 'x';",
                "grammar Demo; start : item EOF; item : value='x';",
                "grammar Demo; start : item EOF; item : 'x' # Value;",
                "grammar Demo; start : item EOF; item : value[1]; value[int n] : 'x';",
            )
        for (grammar in grammars) assertEquals(grammar, grammar, inline(grammar, "item"))
    }

    fun `test one labeled invocation prevents partial inlining of other invocations`() {
        for (label in listOf("v=", "v+=")) {
            val grammar = "grammar Demo; start : item ${label}item EOF; item : 'x' 'y';"
            assertEquals(grammar, inline(grammar, "item"))
        }
    }

    fun `test action references to the removed context prevent inlining`() {
        val grammars =
            listOf(
                "grammar Demo; start : item {use(${'$'}item.text);} EOF; item : 'x';",
                "grammar Demo; start : item value[${'$'}item.text] EOF; item : 'x'; value[String s] : 'y';",
                "grammar Demo; start : item EOF; item : nested; nested : 'x' {use(${'$'}item::value);};",
            )
        for (grammar in grammars) assertEquals(grammar, grammar, inline(grammar, "item"))
    }

    fun `test lexer commands and rule options stay on their original rule`() {
        for (definition in listOf("ITEM : 'x' -> skip;", "ITEM options {caseInsensitive=true;} : 'x';")) {
            val grammar = "lexer grammar Demo; PAIR : ITEM 'y'; $definition"
            assertEquals(grammar, inline(grammar, "ITEM"))
        }
    }

    fun `test inserting an implicit token reference cannot capture a caller action`() {
        val grammar = "grammar Demo; start : ID item {use(${'$'}ID.text);} EOF; item : ID; ID : [a-z]+;"
        assertRejected(grammar, "item")
    }

    fun `test explicit caller labels and actions in unrelated rules remain valid`() {
        val original =
            "grammar Demo; start : saved=ID item {use(${'$'}saved.text);} EOF; other : ID {use(${'$'}ID.text);}; item : ID; ID : [a-z]+;"
        val result = inline(original, "item")
        assertTrue(result.contains("start : saved=ID ID {use(${'$'}saved.text);} EOF;"))
        assertTrue(result.contains("other : ID {use(${'$'}ID.text);};"))
        assertEquals(0, Grammar(result).tool.numErrors)
    }

    fun `test rule-like text in action comments does not block an unrelated inline`() {
        val original = "grammar Demo; start : item {/* ${'$'}item.text */ done();} EOF; item : 'x';"
        val result = inline(original, "item")
        assertEquals("grammar Demo; start : 'x' {/* ${'$'}item.text */ done();} EOF; ", result)
    }

    fun `test invocation options lexer sets and caller lexer options are rejected`() {
        assertRejected("grammar Demo; start : item<p=1> EOF; item : 'x';", "item")
        assertRejected("lexer grammar Demo; PAIR : ~ITEM; fragment ITEM : 'x';", "ITEM")
        assertRejected("lexer grammar Demo; PAIR options {caseInsensitive=true;} : ITEM; fragment ITEM : 'x';", "ITEM")
        val precedence = "grammar Demo; start : item EOF; item : <assoc=right> item '^' item | 'x';"
        assertEquals(0, Grammar(precedence).tool.numErrors)
        assertRejected(precedence, "item")
    }

    fun `test malformed unknown duplicate and unused rules do not produce plans`() {
        assertRejected("grammar Demo; start : item % EOF; item : 'x';", "item")
        assertRejected("grammar Demo; start : 'x';", "missing")
        assertRejected("grammar Demo; start : item; item : 'x'; item : 'y';", "item")
        assertRejected("grammar Demo; start : 'x'; item : 'y';", "item")
    }

    fun `test body comments survive and trailing line comments cannot hide caller syntax`() {
        val original = "grammar Demo; start : item+ EOF; item : /* prefix */ 'x' // suffix\n;"
        val result = inline(original, "item")
        assertTrue(result.contains("(/* prefix */ 'x' // suffix\n)+ EOF;"))
        assertSameLanguage(original, result, listOf("", "x", "xx", "y"))
    }

    fun `test alternatives quantified sequences and nongreedy suffixes preserve accepted input`() {
        for (suffix in listOf("", "?", "*", "+", "+?")) {
            val original = "grammar Demo; start : item$suffix EOF; item : 'a' 'b' | 'c';"
            val result = inline(original, "item")
            assertFalse(original == result)
            assertSameLanguage(original, result, listOf("", "a", "b", "ab", "c", "abc", "cc", "abab", "ac"))
        }
    }

    fun `test empty rules and comment-only bodies inline without removing surrounding elements`() {
        for (body in listOf("", "/* empty */", "// empty\n")) {
            val original = "grammar Demo; start : 'a' item 'b' EOF; item : $body;"
            val result = inline(original, "item")
            assertFalse(original == result)
            assertSameLanguage(original, result, listOf("", "ab", "a", "b", "abab"))
        }
    }

    fun `test inline plans reject stale documents read-only text and guards before any edit`() {
        val grammar = "grammar Demo; start : item item EOF; item : 'x';"
        val result = inlineRule(grammar, "item")
        assertTrue(result is InlineRuleResult.Ready)
        val plan = (result as InlineRuleResult.Ready).plan
        val document = EditorFactory.getInstance().createDocument(grammar)
        WriteCommandAction.runWriteCommandAction(project) {
            document.insertString(0, "// changed\n")
            assertFalse(plan.applyTo(document))
            assertEquals("// changed\n$grammar", document.text)
            document.setText(grammar)
            document.setReadOnly(true)
            assertFalse(plan.applyTo(document))
            document.setReadOnly(false)
            val first = grammar.indexOf("item")
            val guard = document.createGuardedBlock(first, first + 4)
            try {
                assertFalse(plan.applyTo(document))
                assertEquals(grammar, document.text)
            } finally {
                document.removeGuardedBlock(guard)
            }
            assertTrue(plan.applyTo(document))
            assertEquals(plan.resultText(), document.text)
        }
    }

    private fun assertRejected(grammar: String, name: String) {
        val result = inlineRule(grammar, name)
        assertTrue("Expected rejection: $grammar; got $result", result is InlineRuleResult.Rejected)
        assertTrue((result as InlineRuleResult.Rejected).reason.isNotBlank())
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

    private fun inline(grammar: String, ruleName: String): String {
        val result = inlineRule(grammar, ruleName)
        if (result is InlineRuleResult.Rejected) return grammar
        val plan = (result as InlineRuleResult.Ready).plan
        val document = EditorFactory.getInstance().createDocument(grammar)
        WriteCommandAction.runWriteCommandAction(project) { assertTrue(plan.applyTo(document)) }
        return document.text
    }
}
