package com.antlr4plus.actions

import com.antlr4plus.dotG4Support.refactor.ExtractRuleSelection
import junit.framework.TestCase
import org.antlr.v4.Tool
import org.antlr.v4.runtime.BaseErrorListener
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.RecognitionException
import org.antlr.v4.runtime.Recognizer
import org.antlr.v4.runtime.Token
import org.antlr.v4.tool.ANTLRMessage
import org.antlr.v4.tool.ANTLRToolListener
import org.antlr.v4.tool.Grammar

class ExtractRuleCaseOptionsTest : TestCase() {
    fun testLocalEnableOverridePreservesCaseInsensitiveMatching() {
        checkExtraction(
            "lexer grammar Demo; WORD options {caseInsensitive=true;} : [a-z]+;",
            "[a-z]+",
            listOf("abc", "ABC", "aBc"),
            listOf("123", "", "a1"),
        )
    }

    fun testLocalDisableOverridePreservesCaseSensitiveMatching() {
        checkExtraction(
            "lexer grammar Demo; options {caseInsensitive=true;} WORD options {caseInsensitive=false;} : [a-z]+;",
            "[a-z]+",
            listOf("abc", "xyz"),
            listOf("ABC", "aBc", "123", ""),
        )
    }

    fun testGlobalAndDefaultOptionsRemainInherited() {
        for (enabled in listOf(null, false, true)) {
            val options = enabled?.let { "options {caseInsensitive=$it;}" }.orEmpty()
            val result =
                checkExtraction(
                    "lexer grammar Demo; $options WORD : [a-z]+;",
                    "[a-z]+",
                    if (enabled == true) listOf("abc", "ABC", "aBc") else listOf("abc"),
                    if (enabled == true) listOf("123", "") else listOf("ABC", "aBc", "123", ""),
                )
            assertTrue("The new fragment must keep inheriting grammar options", result.contains("fragment PART :"))
        }
    }

    fun testPartialExtractionPreservesRangesAndSurroundingLiterals() {
        checkExtraction(
            "lexer grammar Demo; WORD options {caseInsensitive=true;} : 'pre' ('a'..'z' | '_')+ 'end';",
            "('a'..'z' | '_')+",
            listOf("preabcend", "PREABCEND", "PrE_aEnD"),
            listOf("preend", "pre123end", "xpreabcend", "preabc", ""),
        )
    }

    fun testExtractionFromExistingFragmentsPreservesTheirOwnOverrides() {
        for (enabled in listOf(true, false)) {
            checkExtraction(
                "lexer grammar Demo; options {caseInsensitive=${!enabled};} WORD: BODY; fragment BODY options {caseInsensitive=$enabled;} : 'if';",
                "'if'",
                if (enabled) listOf("if", "IF", "iF") else listOf("if"),
                if (enabled) listOf("i", "ifx", "") else listOf("IF", "iF", "ifx", ""),
            )
        }
    }

    fun testLexerModeAndCommandsRemainOnTheOriginalTokenRule() {
        val result =
            checkExtraction(
                "lexer grammar Demo; options {caseInsensitive=true;} OPEN: '<' -> pushMode(INNER), skip; mode INNER; WORD options {caseInsensitive=false;} : [a-z]+ -> popMode; mode LAST; CLOSE: '>';",
                "[a-z]+",
                listOf("<abc", "<abc<def"),
                listOf("<ABC", "<aBc", "<123", ""),
            )
        assertTrue(result.contains("WORD options {caseInsensitive=false;} : PART -> popMode;"))
        assertTrue(result.indexOf("fragment PART") > result.indexOf("mode INNER"))
        assertTrue(result.indexOf("fragment PART") < result.indexOf("mode LAST"))
    }

    fun testCombinedGrammarKeepsParserAcceptance() {
        checkExtraction(
            "grammar Demo; options {caseInsensitive=true;} start: WORD EOF; WORD options {caseInsensitive=false;} : 'abc';",
            "'abc'",
            listOf("abc"),
            listOf("ABC", "aBc", "ab", "abcabc", ""),
        )
    }

    fun testCommentsAndUnicodeDoNotChangeOptionOrSelectionBoundaries() {
        val options =
            "options { // preserve the local override\n /* 😀 */ caseInsensitive /* match letters */ = true; }"
        val result =
            checkExtraction(
                "lexer grammar Demo; /* 😀 */ WORD $options : '🦊' [a-z]+;",
                "[a-z]+",
                listOf("🦊abc", "🦊ABC", "🦊aBc"),
                listOf("🦊123", "🦊", "abc", ""),
            )
        assertTrue(result.contains("/* 😀 */ WORD $options : '🦊' PART;"))
    }

    fun testReferencedFragmentRetainsItsDifferentCaseSetting() {
        checkExtraction(
            "lexer grammar Demo; WORD options {caseInsensitive=true;} : 'a' EXACT; fragment EXACT options {caseInsensitive=false;} : 'bc';",
            "'a' EXACT",
            listOf("abc", "Abc"),
            listOf("aBC", "ABC", "ab", ""),
        )
    }

    private fun checkExtraction(
        source: String,
        selected: String,
        accepted: List<String>,
        rejected: List<String>,
    ): String {
        val before = compile(source)
        val start = source.indexOf(selected)
        assertTrue("Selection must exist in the fixture", start >= 0)
        val selection = ExtractRuleSelection.find(source, start, start + selected.length)
        assertNotNull("Extraction must remain available for lexer options", selection)
        val plan = selection!!.plan("PART")
        assertNotNull(selection.nameError("PART"), plan)
        val result = plan!!.resultText()
        val after = compile(result)
        for ((input, expectedAcceptance) in accepted.map { it to true } + rejected.map { it to false }) {
            val original = simulate(before, input)
            val extracted = simulate(after, input)
            assertEquals("Original grammar acceptance for $input", expectedAcceptance, original.acceptsWords(before))
            assertEquals(
                "Extraction must preserve acceptance for $input: $result",
                expectedAcceptance,
                extracted.acceptsWords(after),
            )
            assertEquals(
                "Extraction must preserve token types/text/channels, errors and final lexer mode for $input",
                original,
                extracted,
            )
        }
        return result
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

    private fun simulate(grammar: Grammar, input: String): Snapshot {
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
        val lexer = (grammar.implicitLexer ?: grammar).createLexerInterpreter(CharStreams.fromString(input))
        lexer.removeErrorListeners()
        lexer.addErrorListener(listener)
        val tokens = CommonTokenStream(lexer)
        tokens.fill()
        if (!grammar.isLexer) {
            tokens.seek(0)
            val parser = grammar.createParserInterpreter(tokens)
            parser.removeErrorListeners()
            parser.addErrorListener(listener)
            parser.parse(grammar.getRule("start").index)
        }
        return Snapshot(
            tokens.tokens.filter { it.type != Token.EOF }.map { Triple(it.type, it.text, it.channel) },
            errors,
            lexer._mode,
        )
    }

    private data class Snapshot(val tokens: List<Triple<Int, String, Int>>, val errors: Int, val mode: Int) {
        fun acceptsWords(grammar: Grammar): Boolean =
            errors == 0 && tokens.isNotEmpty() && tokens.all { it.first == grammar.getTokenType("WORD") }
    }
}
