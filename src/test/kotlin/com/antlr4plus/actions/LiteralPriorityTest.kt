package com.antlr4plus.actions

import com.antlr4plus.dotG4Support.generators.LiteralLexerRules
import com.antlr4plus.dotG4Support.refactor.ExtractRuleSelection
import com.antlr4plus.dotG4Support.refactor.GrammarSource
import junit.framework.TestCase
import org.antlr.intellij.plugin.parser.ANTLRv4Parser
import org.antlr.v4.Tool
import org.antlr.v4.runtime.BaseErrorListener
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.RecognitionException
import org.antlr.v4.runtime.Recognizer
import org.antlr.v4.tool.ANTLRMessage
import org.antlr.v4.tool.ANTLRToolListener
import org.antlr.v4.tool.Grammar

class LiteralPriorityTest : TestCase() {
    fun testInvalidCaseOptionsAndUnicodeEscapesAreRejected() {
        assertNull(LiteralLexerRules.from("grammar Bad; options {caseInsensitive='\\q';} start: 'x';"))
        assertNull(LiteralLexerRules.from("grammar Bad; start: '\\u{110000}';"))
    }

    fun testPartialGenerationPreservesCaseFoldedLiteralPriority() {
        val result = generate(folded, "'if'")
        equivalent(folded, result, listOf("ifa", "IFa"), listOf("ifb", "IFb", ""))
        assertEquals(2, lexerRuleCount(result))
    }

    fun testPartialGenerationPreservesEscapedLiteralPriority() {
        val source = "grammar Demo; start: ('if' 'a' | '\\u0069f' 'b') EOF;"
        val result = generate(source, "'if'")
        equivalent(source, result, listOf("ifa"), listOf("ifb", "IFa", ""))
        assertEquals(2, lexerRuleCount(result))
    }

    fun testCompanionDependenciesIncludeTransitiveUnicodeOverlaps() {
        val source = "grammar Demo; options {caseInsensitive=true;} start: ('İ' 'a' | 'i' 'b' | 'ı' 'c') EOF;"
        val result = generate(source, "'İ'")
        equivalent(source, result, listOf("ia", "İa", "Ib", "ıc"), listOf("ib", "Ic", "ıa", "İb", ""))
        assertEquals(3, lexerRuleCount(result))
    }

    fun testSupplementaryCasePairsAndEscapesRetainPriority() {
        val source = "grammar Demo; options {caseInsensitive=true;} start: ('𐐀-' 'a' | '\\u{10428}-' 'b') EOF;"
        val result = generate(source, "'𐐀-'")
        equivalent(source, result, listOf("𐐀-a", "𐐨-a"), listOf("𐐀-b", "𐐨-b", ""))
        assertEquals(2, lexerRuleCount(result))
    }

    fun testFullGenerationAndLaterLiteralsDoNotNeedExtraRules() {
        val generation = LiteralLexerRules.from(folded)!!
        val all = generation.plan(generation.rules, folded.length)!!.resultText()
        equivalent(folded, all, listOf("ifa", "IFa"), listOf("ifb", "IFb", ""))
        assertEquals(4, lexerRuleCount(all))
        val later = generate(folded, "'IF'")
        equivalent(folded, later, listOf("ifa", "IFa"), listOf("ifb", "IFb", ""))
        assertEquals(1, lexerRuleCount(later))
    }

    fun testDistinctLiteralsAndLongestMatchKeepPartialSelectionsSmall() {
        val distinct = "grammar Demo; start: ('if' 'a' | 'IF' 'b') EOF;"
        val result = generate(distinct, "'if'")
        equivalent(distinct, result, listOf("ifa", "IFb"), listOf("ifb", "IFa", ""))
        assertEquals(1, lexerRuleCount(result))
        val prefix = "grammar Demo; options {caseInsensitive=true;} start: ('i' 'a' | 'if' 'b' | 'IF' 'c') EOF;"
        val short = generate(prefix, "'i'")
        equivalent(prefix, short, listOf("ia", "ifb", "IFb"), listOf("ifc", "IFc", ""))
        assertEquals(1, lexerRuleCount(short))
    }

    fun testExistingLexerAliasesKeepTheirPriorityWithoutCompanions() {
        val source = folded + " UPPER: 'IF';"
        val generated = generate(source, "'if'")
        equivalent(source, generated, listOf("ifa", "IFa"), listOf("ifb", "IFb", ""))
        assertEquals(2, lexerRuleCount(generated))
        val extracted = extract(source, source.indexOf("'if'"), "'if'")
        equivalent(source, extracted, listOf("ifa", "IFa"), listOf("ifb", "IFb", ""))
        assertEquals(1, lexerRuleCount(extracted))
    }

    fun testParserExtractionPreservesCaseFoldedPriorityAtEndOfFile() {
        val result = extract(folded, folded.indexOf("'if'"), "'if'")
        equivalent(folded, result, listOf("ifa", "IFa"), listOf("ifb", "IFb", ""))
        assertEquals(1, lexerRuleCount(result))
        assertTrue(result.contains("keyword : 'if' ;"))
    }

    fun testParserExtractionPreservesEscapedLiteralPriority() {
        val source = "grammar Demo; /* 😀 */ start: ('if' 'a' | '\\u0069f' 'b') EOF;"
        val result = extract(source, source.indexOf("'if'"), "'if'")
        equivalent(source, result, listOf("ifa"), listOf("ifb", "IFa", ""))
        assertEquals(1, lexerRuleCount(result))
        assertTrue(result.contains("/* 😀 */"))
    }

    fun testExtractionPlacesCompanionsBeforeExistingLexerRules() {
        val source = folded + " ID: [a-z]+; WS: [ ]+ -> skip;"
        val result = extract(source, source.indexOf("'if'"), "'if'")
        equivalent(source, result, listOf("if a", "IF a"), listOf("if b", "IF b", "other a", ""))
        assertEquals(3, lexerRuleCount(result))
        assertTrue(result.indexOf(" : 'IF' ;") < result.indexOf("ID:"))
    }

    fun testHarmlessExtractionOrderChangesDoNotAddLexerRules() {
        val later = extract(folded, folded.indexOf("'IF'"), "'IF'")
        equivalent(folded, later, listOf("ifa", "IFa"), listOf("ifb", "IFb", ""))
        assertEquals(0, lexerRuleCount(later))
        val repeated = "grammar Demo; options {caseInsensitive=true;} unused: 'if'; start: ('if' 'a' | 'IF' 'b') EOF;"
        val result = extract(repeated, repeated.indexOf("'if'", repeated.indexOf("start:")), "'if'")
        equivalent(repeated, result, listOf("ifa", "IFa"), listOf("ifb", "IFb", ""))
        assertEquals(0, lexerRuleCount(result))
    }

    fun testCompanionNamesAvoidExistingDeclarationsAndTokenNames() {
        val source =
            "grammar Demo; options {caseInsensitive=true;} tokens {IF1} start: ('if' 'a' | 'IF' 'b') EOF; IF: 'other';"
        val generated = generate(source, "'if'")
        equivalent(source, generated, listOf("ifa", "IFa"), listOf("ifb", "IFb", "other", ""))
        assertEquals(3, lexerRuleCount(generated))
        val extracted = extract(source, source.indexOf("'if'"), "'if'")
        equivalent(source, extracted, listOf("ifa", "IFa"), listOf("ifb", "IFb", "other", ""))
        assertEquals(2, lexerRuleCount(extracted))
    }

    private fun generate(source: String, literal: String): String {
        val generation = LiteralLexerRules.from(source)!!
        val selected = generation.rules.single { it.substringAfter(" : ").removeSuffix(" ;") == literal }
        return generation.plan(listOf(selected), source.length)!!.resultText()
    }

    private fun extract(source: String, start: Int, literal: String): String {
        val selection = ExtractRuleSelection.find(source, start, start + literal.length)
        assertNotNull("Extraction must remain available", selection)
        return selection!!.plan("keyword")!!.resultText()
    }

    private fun lexerRuleCount(source: String): Int =
        GrammarSource.parse(source)!!.rules.count { it is ANTLRv4Parser.LexerRuleContext }

    private fun equivalent(source: String, result: String, accepted: List<String>, rejected: List<String>) {
        val before = compile(source)
        val after = compile(result)
        for ((input, expected) in accepted.map { it to true } + rejected.map { it to false }) {
            assertEquals("Original grammar acceptance for $input", expected, accepts(before, input))
            assertEquals("Transformation must preserve acceptance for $input: $result", expected, accepts(after, input))
        }
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

    companion object {
        private const val folded = "grammar Demo; options {caseInsensitive=true;} start: ('if' 'a' | 'IF' 'b') EOF;"
    }
}
