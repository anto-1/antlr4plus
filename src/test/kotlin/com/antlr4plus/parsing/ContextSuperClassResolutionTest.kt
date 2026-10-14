package com.antlr4plus.parsing

import com.antlr4plus.parsing.embedded.GrammarCodeExtractor
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.antlr.v4.tool.Grammar
import org.antlr.v4.tool.LexerGrammar
import org.junit.Test

/** Covers class-valued grammar options in both text extraction and loaded ANTLR grammars. */
class ContextSuperClassResolutionTest {

    private val lexerGrammarText =
        """
        lexer grammar CustomerLexer;

        options {
            superClass = MyLexerBase;
        }

        ID  : [a-zA-Z]+ ;
        WS  : [ \t\r\n]+ -> skip ;
        """
            .trimIndent()

    private val parserGrammarText =
        """
        parser grammar CustomerParser;

        options {
            tokenVocab = CustomerLexer;
            contextSuperClass = MyContextBase;
            TokenLabelType = MyTokenType;
        }

        program : ID+ EOF ;
        """
            .trimIndent()

    private val combinedGrammarText =
        """
        grammar Combined;

        options {
            superClass = CombinedParserBase;
            contextSuperClass = CombinedContextBase;
        }

        prog : ID+ EOF ;
        ID   : [a-zA-Z]+ ;
        WS   : [ \t\r\n]+ -> skip ;
        """
            .trimIndent()

    @Test
    fun `extractSuperClassOptions picks up contextSuperClass on parser grammar`() {
        val refs = GrammarCodeExtractor.extractSuperClassOptions(parserGrammarText)

        val ctx = refs.find { it.option == "contextSuperClass" }
        assertNotNull(ctx, "contextSuperClass should be extracted as a SuperClassRef")
        assertEquals("MyContextBase", ctx.className)
        assertEquals("parser", ctx.type)
    }

    @Test
    fun `extractSuperClassOptions picks up TokenLabelType on parser grammar`() {
        val refs = GrammarCodeExtractor.extractSuperClassOptions(parserGrammarText)

        val tlt = refs.find { it.option == "TokenLabelType" }
        assertNotNull(tlt, "TokenLabelType should be extracted as a SuperClassRef")
        assertEquals("MyTokenType", tlt.className)
        assertEquals("parser", tlt.type)
    }

    @Test
    fun `extractSuperClassOptions still picks up plain superClass on lexer grammar`() {
        val refs = GrammarCodeExtractor.extractSuperClassOptions(lexerGrammarText)

        val sc = refs.find { it.option == "superClass" }
        assertNotNull(sc, "superClass should still be extracted (regression check)")
        assertEquals("MyLexerBase", sc.className)
        assertEquals("lexer", sc.type)
    }

    @Test
    fun `extractSuperClassOptions handles combined grammar with both options`() {
        val refs = GrammarCodeExtractor.extractSuperClassOptions(combinedGrammarText)

        val sc = refs.find { it.option == "superClass" }
        val ctx = refs.find { it.option == "contextSuperClass" }

        assertNotNull(sc)
        assertEquals("CombinedParserBase", sc.className)
        assertEquals("parser", sc.type)

        assertNotNull(ctx)
        assertEquals("CombinedContextBase", ctx.className)
        assertEquals("parser", ctx.type)
    }

    @Test
    fun `extractSuperClassOptions ignores unrelated options like tokenVocab`() {
        val refs = GrammarCodeExtractor.extractSuperClassOptions(parserGrammarText)
        // tokenVocab refers to a grammar rather than a source class.
        assertTrue(
            refs.none { it.option == "tokenVocab" },
            "tokenVocab must not be extracted as a SuperClassRef",
        )
    }

    @Test
    fun `getClassValuedOptions returns contextSuperClass on parser Grammar instance`() {
        val parser = Grammar(parserGrammarText)
        val opts = CompiledParsingService.getClassValuedOptions(parser)

        assertEquals(
            "MyContextBase",
            opts["contextSuperClass"],
            "contextSuperClass must be exposed via getClassValuedOptions",
        )
        assertEquals(
            "MyTokenType",
            opts["TokenLabelType"],
            "TokenLabelType must be exposed via getClassValuedOptions",
        )

        assertNull(opts["superClass"])
    }

    @Test
    fun `getClassValuedOptions returns superClass on lexer Grammar instance`() {
        val lexer = LexerGrammar(lexerGrammarText)
        val opts = CompiledParsingService.getClassValuedOptions(lexer)

        assertEquals("MyLexerBase", opts["superClass"])
        assertNull(opts["contextSuperClass"])
        assertNull(opts["TokenLabelType"])
    }

    @Test
    fun `getContextSuperClass and getTokenLabelType helpers return raw option values`() {
        val parser = Grammar(parserGrammarText)
        assertEquals("MyContextBase", CompiledParsingService.getContextSuperClass(parser))
        assertEquals("MyTokenType", CompiledParsingService.getTokenLabelType(parser))
        assertNull(CompiledParsingService.getSuperClass(parser))
    }

    @Test
    fun `hasSuperClass is true when only contextSuperClass is set`() {
        val parser = Grammar(parserGrammarText)
        assertTrue(
            CompiledParsingService.hasSuperClass(parser),
            "hasSuperClass must report true when contextSuperClass is declared even " + "without a plain superClass",
        )
    }

    @Test
    fun `customer scenario - lexer superClass and parser contextSuperClass union`() {
        // Dependency search needs the union of class names from both grammars.
        val lexer = LexerGrammar(lexerGrammarText)
        val parser = Grammar(parserGrammarText)

        val lexerOpts = CompiledParsingService.getClassValuedOptions(lexer)
        val parserOpts = CompiledParsingService.getClassValuedOptions(parser)

        val classNamesToFind = (lexerOpts.values + parserOpts.values).map { it.substringAfterLast('.') }.toSet()

        assertTrue(
            "MyLexerBase" in classNamesToFind,
            "Lexer base class must be queued for search",
        )
        assertTrue(
            "MyContextBase" in classNamesToFind,
            "Parser context base class must be queued for search " + "(this is the customer's primary bug)",
        )
        assertTrue(
            "MyTokenType" in classNamesToFind,
            "Token label type must be queued for search",
        )
        assertEquals(
            3,
            classNamesToFind.size,
            "Exactly three distinct class names should be searched for",
        )
    }
}
