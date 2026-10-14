package com.antlr4plus.parsing.actionMap

import com.antlr4plus.parsing.embedded.GrammarCodeExtractor
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Uses an action-free AWK-like fixture; GrammarsV4FixtureIntegrationTest covers the upstream checkout. */
class RealAwkGrammarTest {

    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun `action-free grammar produces an empty embedded-code analysis`() {
        val grammarFile = temporaryFolder.newFile("AwkLike.g4")
        grammarFile.writeText(actionFreeGrammar)

        val embeddedCode = GrammarCodeExtractor.extract(grammarFile.readText(), grammarFile.absolutePath)

        assertEquals(emptyList(), embeddedCode)
    }

    @Test
    fun `action-free grammar produces an empty source map without failing`() {
        val grammarFile = temporaryFolder.newFile("AwkLike.g4")
        val generatedFile = temporaryFolder.newFile("AwkLikeParser.java")
        grammarFile.writeText(actionFreeGrammar)
        generatedFile.writeText("public final class AwkLikeParser {}")

        val map = ActionLocationMapBuilder.build(grammarFile, generatedFile)

        assertEquals(emptyList(), map?.entries)
    }

    private val actionFreeGrammar =
        """
        grammar AwkLike;
        program : WORD* EOF;
        WORD : [a-zA-Z]+;
        WS : [ \t\r\n]+ -> skip;
        """
            .trimIndent()
}
