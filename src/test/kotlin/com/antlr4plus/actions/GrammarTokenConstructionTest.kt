package com.antlr4plus.actions

import com.antlr4plus.dotG4Support.refactor.RefactorUtils
import com.antlr4plus.dotG4Support.validation.AddTokenDefinitionFix
import java.util.Locale
import junit.framework.TestCase
import org.antlr.v4.runtime.BaseErrorListener
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.RecognitionException
import org.antlr.v4.runtime.Recognizer
import org.antlr.v4.runtime.Token
import org.antlr.v4.tool.Grammar

/** Runs grammars assembled from the actual token-construction helpers under different IDE locales. */
class GrammarTokenConstructionTest : TestCase() {
    fun testLiteralRulesKeepTokenReferencesAndOriginalTextAcrossLocales() {
        for (locale in listOf(Locale.ROOT, Locale.forLanguageTag("tr-TR"))) {
            withLocale(locale) {
                val literals = listOf("'if'", "'('", "'print'", "','", "'import'", "')'")
                val definitions =
                    literals.joinToString("\n") {
                        "${RefactorUtils.getLexerRuleNameFromLiteral(it)} : $it;"
                    }
                val grammar = compile("grammar Keywords; start: IF LPAREN PRINT COMMA IMPORT RPAREN EOF;\n$definitions")
                val result = parse(grammar, "if(print,import)")
                assertTrue("$locale must preserve grammar token references: ${result.errors}", result.errors.isEmpty())
                assertEquals(
                    listOf("IF", "LPAREN", "PRINT", "COMMA", "IMPORT", "RPAREN"),
                    result.tokens.map { it.first },
                )
                assertEquals(listOf("if", "(", "print", ",", "import", ")"), result.tokens.map { it.second })
                for (rejected in listOf("IF(print,import)", "if(print)", "if(print,import")) {
                    assertFalse(
                        "Generated literals must retain their language: $rejected",
                        parse(grammar, rejected).errors.isEmpty(),
                    )
                }
            }
        }
    }

    fun testFragmentDefinitionsCompileAndAcceptMixedCaseInputAcrossLocales() {
        for (locale in listOf(Locale.ROOT, Locale.forLanguageTag("tr-TR"))) {
            withLocale(locale) {
                val expression = AddTokenDefinitionFix.buildTokenDefinitionExpressionText("Print_2")
                val grammar =
                    compile(
                        "grammar Fragments; start: Print_2 EOF; Print_2: $expression; " +
                            "fragment P:[pP]; fragment R:[rR]; fragment I:[iI]; fragment N:[nN]; fragment T:[tT];",
                    )
                for (input in listOf("print_2", "PrInT_2", "PRINT_2")) {
                    val result = parse(grammar, input)
                    assertTrue(
                        "$locale must produce resolvable letter fragments: ${result.errors}",
                        result.errors.isEmpty(),
                    )
                    assertEquals(listOf("Print_2" to input), result.tokens)
                }
                for (rejected in listOf("print", "print_3", "print2", "print__2", "prınt_2")) {
                    assertFalse(
                        "Quoted digits, separators and fragment alphabets must be retained: $rejected",
                        parse(grammar, rejected).errors.isEmpty(),
                    )
                }
            }
        }
    }

    private fun compile(source: String): Grammar =
        Grammar(source).also {
            assertEquals("Generated grammar must compile: $source", 0, it.tool.numErrors)
        }

    private data class Parsed(val tokens: List<Pair<String?, String?>>, val errors: List<String>)

    private fun parse(grammar: Grammar, input: String): Parsed {
        val errors = mutableListOf<String>()
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
                    errors.add(msg ?: "syntax error")
                }
            }
        val lexer = grammar.implicitLexer.createLexerInterpreter(CharStreams.fromString(input))
        lexer.removeErrorListeners()
        lexer.addErrorListener(listener)
        val tokens = CommonTokenStream(lexer)
        val parser = grammar.createParserInterpreter(tokens)
        parser.removeErrorListeners()
        parser.addErrorListener(listener)
        parser.parse(grammar.getRule("start").index)
        tokens.fill()
        return Parsed(
            tokens.tokens.filter { it.type != Token.EOF }.map { parser.vocabulary.getSymbolicName(it.type) to it.text },
            errors,
        )
    }

    private fun withLocale(locale: Locale, action: () -> Unit) {
        val original = Locale.getDefault()
        val display = Locale.getDefault(Locale.Category.DISPLAY)
        val format = Locale.getDefault(Locale.Category.FORMAT)
        try {
            Locale.setDefault(locale)
            action()
        } finally {
            Locale.setDefault(original)
            Locale.setDefault(Locale.Category.DISPLAY, display)
            Locale.setDefault(Locale.Category.FORMAT, format)
        }
    }
}
