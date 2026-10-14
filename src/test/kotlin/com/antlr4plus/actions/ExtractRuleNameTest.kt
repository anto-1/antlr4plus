package com.antlr4plus.actions

import com.antlr4plus.dotG4Support.refactor.ExtractRuleSelection
import junit.framework.TestCase
import org.antlr.v4.Tool
import org.antlr.v4.runtime.BaseErrorListener
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.RecognitionException
import org.antlr.v4.runtime.Recognizer
import org.antlr.v4.tool.ANTLRMessage
import org.antlr.v4.tool.ANTLRToolListener
import org.antlr.v4.tool.Grammar

class ExtractRuleNameTest : TestCase() {
    fun testLexerConstantsAreRejectedBeforePlanning() {
        val names =
            listOf(
                "HIDDEN",
                "DEFAULT_TOKEN_CHANNEL",
                "DEFAULT_MODE",
                "SKIP",
                "MORE",
                "EOF",
                "MAX_CHAR_VALUE",
                "MIN_CHAR_VALUE",
            )
        for (source in listOf(lexerSource, combinedSource)) {
            compile(source)
            val selection = selection(source, "[a-z]+")
            for (name in names) {
                assertNotNull("Reserved name must be rejected: $name", selection.nameError(name))
                assertNull("Reserved name must not produce edits: $name", selection.plan(name))
            }
        }
    }

    fun testImplicitTokenNamespaceIsRejectedBeforePlanning() {
        for (source in listOf(lexerSource, combinedSource)) {
            compile(source)
            val selection = selection(source, "[a-z]+")
            for (name in listOf("T__0", "T__1", "T__42", "T__", "T__WORD")) {
                assertNotNull("Implicit-token namespace must be rejected: $name", selection.nameError(name))
                assertNull("Implicit-token namespace must not produce edits: $name", selection.plan(name))
            }
        }
    }

    fun testSimilarLexerNamesStillCompileAndPreserveTokens() {
        val names =
            listOf(
                "HIDDEN1",
                "DEFAULT_TOKEN_CHANNEL1",
                "DEFAULT_MODE1",
                "SKIP_TOKEN",
                "MORE1",
                "EOF1",
                "MAX_CHAR_VALUE1",
                "MIN_CHAR_VALUE1",
                "T_0",
                "TT__0",
            )
        for (source in listOf(lexerSource, combinedSource)) {
            val before = compile(source)
            val selection = selection(source, "[a-z]+")
            for (name in names) {
                assertNull(name, selection.nameError(name))
                val after = compile(selection.plan(name)!!.resultText())
                for (input in listOf("", "abc", "x word", "y\nword")) {
                    val expected = tokens(before, input)
                    assertEquals("Original lexer must accept $input", 0, expected.second)
                    assertEquals(
                        "Extraction as $name must retain token types, text and channels",
                        expected,
                        tokens(after, input),
                    )
                }
                val invalid = tokens(before, "123")
                assertTrue("Negative control must contain lexer errors", invalid.second > 0)
                assertEquals(invalid, tokens(after, "123"))
            }
        }
    }

    fun testLowercaseParserNamesStillCompileAndPreserveLanguage() {
        // Keep every proposed name unused: a skip command already counts as a reference to skip.
        val source = combinedSource.replace("-> skip", "-> channel(HIDDEN)")
        val before = compile(source)
        val selection = selection(source, "'x'")
        for (name in
            listOf(
                "hidden",
                "default_token_channel",
                "default_mode",
                "skip",
                "more",
                "eof",
                "max_char_value",
                "min_char_value",
                "t__0",
            )) {
            assertNull(name, selection.nameError(name))
            val after = compile(selection.plan(name)!!.resultText())
            for (input in listOf("x word", "x abc", "y word")) {
                assertTrue("Original grammar must accept $input", accepts(before, input))
                assertTrue("Extraction as $name must still accept $input", accepts(after, input))
            }
            for (input in listOf("", "word", "x", "xx", "x x", "123")) {
                assertFalse("Original grammar must reject $input", accepts(before, input))
                assertFalse("Extraction as $name must still reject $input", accepts(after, input))
            }
        }
    }

    private fun selection(source: String, selected: String): ExtractRuleSelection {
        val start = source.indexOf(selected)
        assertTrue(start >= 0)
        return ExtractRuleSelection.find(source, start, start + selected.length) ?: error("Invalid test selection")
    }

    private fun compile(source: String): Grammar {
        val errors = mutableListOf<String>()
        val tool = Tool()
        tool.removeListeners()
        tool.addListener(
            object : ANTLRToolListener {
                override fun info(msg: String) {}

                override fun warning(msg: ANTLRMessage) {}

                override fun error(msg: ANTLRMessage) {
                    errors.add(msg.toString())
                }
            },
        )
        val ast = tool.parseGrammarFromString(source)
        assertEquals("Grammar syntax errors: $source", emptyList<String>(), errors)
        val grammar = tool.createGrammar(ast)
        tool.process(grammar, false)
        assertEquals("Grammar semantic errors: $source", emptyList<String>(), errors)
        return grammar
    }

    private fun tokens(grammar: Grammar, input: String): Pair<List<Triple<Int, String, Int>>, Int> {
        val errors = Errors()
        val lexer = (grammar.implicitLexer ?: grammar).createLexerInterpreter(CharStreams.fromString(input))
        lexer.removeErrorListeners()
        lexer.addErrorListener(errors)
        return lexer.allTokens.map { Triple(it.type, it.text, it.channel) } to errors.count
    }

    private fun accepts(grammar: Grammar, input: String): Boolean {
        val errors = Errors()
        val lexer = grammar.implicitLexer.createLexerInterpreter(CharStreams.fromString(input))
        lexer.removeErrorListeners()
        lexer.addErrorListener(errors)
        val parser = grammar.createParserInterpreter(CommonTokenStream(lexer))
        parser.removeErrorListeners()
        parser.addErrorListener(errors)
        parser.parse(grammar.getRule("start").index)
        return errors.count == 0
    }

    private class Errors : BaseErrorListener() {
        var count = 0

        override fun syntaxError(
            recognizer: Recognizer<*, *>?,
            offendingSymbol: Any?,
            line: Int,
            charPositionInLine: Int,
            msg: String?,
            e: RecognitionException?,
        ) {
            count++
        }
    }

    companion object {
        private const val lexerSource = "lexer grammar Demo; WORD: [a-z]+; WS: [ \\t\\r\\n]+ -> skip;"
        private const val combinedSource =
            "grammar Demo; start: ('x' | 'y') WORD EOF; WORD: [a-z]+; WS: [ \\t\\r\\n]+ -> skip;"
    }
}
