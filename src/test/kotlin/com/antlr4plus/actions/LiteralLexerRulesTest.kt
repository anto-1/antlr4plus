package com.antlr4plus.actions

import com.antlr4plus.dotG4Support.generators.LiteralLexerRules
import java.util.Locale
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

class LiteralLexerRulesTest : TestCase() {
    fun testPunctuationSpacesAndEscapesProduceCompilableRules() {
        val source = "grammar Punctuation; start: ('foo-bar' | 'else if' | 'it\\'s' | 'a.b') EOF;"
        checkLanguage(source, listOf("foo-bar", "else if", "it's", "a.b"), listOf("foo", "else", "its", "ab"))
    }

    fun testCaseCollisionsKeepDistinctLiteralDefinitions() {
        checkLanguage("grammar Cases; start: ('if' | 'IF') EOF;", listOf("if", "IF"), listOf("If", "iF", ""))
    }

    fun testNamesAvoidDeclarationsTokenBlocksAndUnresolvedReferences() {
        val source = "grammar Names; tokens { IF2 } start: ('if' | IF | IF1 | IF3) EOF; IF: 'other'; IF1: 'another';"
        val generation = generation(source)
        assertEquals(1, generation.rules.size)
        assertFalse(generation.rules.single().substringBefore(" : ") in setOf("IF", "IF1", "IF2", "IF3", "EOF"))
        checkLanguage(source, listOf("if", "other", "another"), listOf("IF3", ""))
    }

    fun testReservedAndImplicitTokenNamesRemainSafeWhenOnlySomeRulesAreSelected() {
        val source = "grammar Reserved; start: ('eof' | 'T__0' | 'other') EOF;"
        val generation = generation(source)
        for (selected in listOf(generation.rules, generation.rules.filter { "'T__0'" in it })) {
            assertEquivalent(source, rendered(generation, selected), listOf("eof", "T__0", "other"), listOf("EOF", ""))
        }
    }

    fun testFallbackNamesAreDeterministicAndRepeatedLiteralsAreDeduplicated() {
        val source = "grammar Stable; start: ('123' | '🦊' | '??' | '123') EOF;"
        val first = generation(source).rules
        assertEquals(first, generation(source).rules)
        assertEquals(3, first.size)
        assertEquals(3, first.map { it.substringBefore(" : ") }.toSet().size)
        checkLanguage(source, listOf("123", "🦊", "??"), listOf("12", "?", ""))
    }

    fun testLexerAliasesSuppressCandidatesWhileFragmentsRemainAvailable() {
        val source =
            "grammar Defined; start: ('if' | 'print' | 'checked' | 'acted' | 'frag' | '(') EOF; IF: 'if'; PRINT: 'print' -> channel(HIDDEN); CHECKED: 'checked' {true}?; ACTED: 'acted' {touch();}; fragment FRAG: 'frag';"
        val generation = generation(source)
        assertEquals(listOf("FRAG1 : 'frag' ;", "LPAREN : '(' ;"), generation.rules)
        checkLanguage(source, listOf("if", "checked", "acted", "frag", "("), listOf("print", ""))
    }

    fun testAntlrLexerConstantsAreReserved() {
        val literals =
            listOf(
                "hidden",
                "default_token_channel",
                "default_mode",
                "skip",
                "more",
                "max_char_value",
                "min_char_value",
            )
        val source = "grammar Constants; start: (" + literals.joinToString(" | ") { "'$it'" } + ") EOF;"
        checkLanguage(source, literals, listOf("HIDDEN", "skipmore", ""))
    }

    fun testOptionStringsAreNotTokenCandidates() {
        val generation = generation("grammar Metadata; start: 'if'<custom='metadata'> EOF;")
        assertEquals(listOf("IF : 'if' ;"), generation.rules)
    }

    fun testUnsupportedOrMalformedGrammarsAreRejected() {
        for (source in
            listOf(
                "parser grammar ParserOnly; start: 'x';",
                "lexer grammar LexerOnly; WORD: 'x';",
                "grammar Broken; start: 'x'",
            )) {
            assertNull(source, LiteralLexerRules.from(source))
        }
    }

    fun testConventionalNamesLiteralOrderAndDedicatedFilteringRemainStable() {
        val source = "grammar Simple; start: 'if' '(' 'print' ')' 'print' EOF; IF: 'if';"
        assertEquals(listOf("LPAREN : '(' ;", "PRINT : 'print' ;", "RPAREN : ')' ;"), generation(source).rules)
        checkLanguage(source, listOf("if(print)print"), listOf("if(PRINT)print", "if(print)", ""))
    }

    fun testPlansPreserveUnicodeCommentsAdjacentRulesAndLexerPriority() {
        val source = "grammar Unicode; /* 😀 */ start: 'if' EOF;tail: 'z'; // keep\nID: [a-z]+;"
        val generation = generation(source)
        for (caret in listOf(0, source.indexOf("'if'") + 2, source.indexOf("tail"), source.length)) {
            val result = generation.plan(generation.rules, caret)!!.resultText()
            assertTrue(result.contains("/* 😀 */"))
            assertTrue(result.contains("tail: 'z'; // keep"))
            assertTrue(result.indexOf("IF :") < result.indexOf("ID:"))
            assertEquivalent(source, result, listOf("if"), listOf("IF", "ix", "z", ""))
        }
    }

    fun testSelectionKeepsSourceOrderAndRejectsUnknownRules() {
        val source = "grammar Selection; start: ('if' | 'IF' | 'other') EOF;"
        val generation = generation(source)
        val selected = listOf(generation.rules[1], generation.rules[0])
        val result = generation.plan(selected, source.length)!!.resultText()
        assertTrue(result.indexOf(generation.rules[0]) < result.indexOf(generation.rules[1]))
        assertFalse(result.contains(generation.rules[2]))
        assertEquivalent(source, result, listOf("if", "IF", "other"), listOf("If", ""))
        assertNull(generation.plan(emptyList(), 0))
        assertNull(generation.plan(listOf("UNAPPROVED : 'injected' ;"), 0))
        assertNull(generation.plan(selected, -1))
        assertNull(generation.plan(selected, source.length + 1))
    }

    fun testEmptyLiteralsAreNotOffered() {
        assertEquals(emptyList<String>(), generation("grammar Empty; start: '' EOF;").rules)
    }

    fun testCandidateNamesAreIndependentOfLocale() {
        val original = Locale.getDefault()
        val display = Locale.getDefault(Locale.Category.DISPLAY)
        val format = Locale.getDefault(Locale.Category.FORMAT)
        val source = "grammar Locales; start: ('if' | 'IF' | 'foo-bar' | 'éclair') EOF;"
        try {
            Locale.setDefault(Locale.ROOT)
            val expected = generation(source).rules
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals(expected, generation(source).rules)
            checkLanguage(source, listOf("if", "IF", "foo-bar", "éclair"), listOf("İF", "ECLAIR", ""))
        } finally {
            Locale.setDefault(original)
            Locale.setDefault(Locale.Category.DISPLAY, display)
            Locale.setDefault(Locale.Category.FORMAT, format)
        }
    }

    private fun generation(text: String): LiteralLexerRules = LiteralLexerRules.from(text) ?: error("Invalid fixture")

    private fun rendered(generation: LiteralLexerRules, selected: List<String> = generation.rules): String =
        generation.plan(selected, generation.source.text.length)!!.resultText()

    private fun checkLanguage(source: String, accepted: List<String>, rejected: List<String>) {
        val generation = generation(source)
        assertEquivalent(source, rendered(generation), accepted, rejected)
    }

    private fun assertEquivalent(original: String, generated: String, accepted: List<String>, rejected: List<String>) {
        val before = compile(original)
        val after = compile(generated)
        for (input in accepted) {
            assertTrue("Original grammar must accept $input", accepts(before, input))
            assertTrue("Generated grammar must still accept $input: $generated", accepts(after, input))
        }
        for (input in rejected) {
            assertFalse("Original grammar must reject $input", accepts(before, input))
            assertFalse("Generated grammar must still reject $input", accepts(after, input))
        }
    }

    private fun compile(text: String): Grammar {
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
        val ast = tool.parseGrammarFromString(text)
        assertEquals("Grammar syntax errors: $text", emptyList<String>(), errors)
        val grammar = tool.createGrammar(ast)
        tool.process(grammar, false)
        assertEquals("Grammar semantic errors: $text", emptyList<String>(), errors)
        return grammar
    }

    private fun accepts(grammar: Grammar, input: String): Boolean {
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
        val lexer = grammar.implicitLexer.createLexerInterpreter(CharStreams.fromString(input))
        lexer.removeErrorListeners()
        lexer.addErrorListener(listener)
        val parser = grammar.createParserInterpreter(CommonTokenStream(lexer))
        parser.removeErrorListeners()
        parser.addErrorListener(listener)
        parser.parse(grammar.getRule("start").index)
        return errors == 0
    }
}
