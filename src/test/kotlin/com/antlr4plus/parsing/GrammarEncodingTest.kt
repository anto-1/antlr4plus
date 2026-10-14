package com.antlr4plus.parsing

import com.antlr4plus.parsing.actionMap.ActionLocationMapBuilder
import com.antlr4plus.parsing.embedded.GrammarCodeExtractor
import com.antlr4plus.util.GeneratedWorkspaceFileOps
import java.io.File
import java.nio.charset.StandardCharsets
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Test

class GrammarEncodingTest {

    private lateinit var tempDir: File

    @After
    fun cleanup() {
        if (::tempDir.isInitialized) {
            assertTrue(GeneratedWorkspaceFileOps.deleteTree(tempDir.toPath()))
        }
    }

    @Test
    fun `action map decodes grammar with selected UTF-16 encoding`() {
        val root = createTempDirectory("grammar-encoding-map-").toFile().canonicalFile.also { tempDir = it }
        val grammar = File(root, "Encoded.g4")
        val generated = File(root, "EncodedParser.java")
        grammar.writeText(
            """
            grammar Encoded;
            start : ID { dangerous(); } EOF ;
            ID : [a-z]+ ;
            WS : [ \t\r\n]+ -> skip ;
            """
                .trimIndent(),
            StandardCharsets.UTF_16LE,
        )
        generated.writeText(
            """
            public class EncodedParser extends Parser {
                public final StartContext start() {
                    match(ID);
                     dangerous();
                    match(EOF);
                    return _localctx;
                }
            }
            """
                .trimIndent(),
        )

        val map =
            ActionLocationMapBuilder.build(
                grammar,
                generated,
                StandardCharsets.UTF_16LE,
            )

        assertNotNull(map)
        assertEquals(1, map.entries.size)
        assertEquals("dangerous();", map.entries.single().body)
    }

    @Test
    fun `analysis uses the current editor snapshot instead of stale disk content`() {
        val root = createTempDirectory("grammar-language-snapshot-").toFile().canonicalFile.also { tempDir = it }
        val grammar = File(root, "Snapshot.g4")
        grammar.writeText(
            "grammar Snapshot; options { language = Java; } start : EOF { System.out.println(); };",
        )
        val currentEditorText = "grammar Snapshot; options { language = Python3; } start : EOF { print('current') };"

        val embeddedCode =
            GrammarCodeExtractor.extract(
                currentEditorText,
                grammar.absolutePath,
            )

        assertTrue(embeddedCode.any { "print('current')" in it.content })
        assertTrue(embeddedCode.none { "System.out.println" in it.content })
        assertEquals(
            GrammarLanguageDetector.TargetLanguage.PYTHON,
            GrammarLanguageDetector.detectTargetLanguage(currentEditorText).detectedLanguage,
        )
    }

    @Test
    fun `preview language and support-class detection honor selected UTF-16 encoding`() {
        val root = createTempDirectory("grammar-preview-encoding-").toFile().canonicalFile.also { tempDir = it }
        val grammar = File(root, "EncodedPython.g4")
        grammar.writeText(
            """
            grammar EncodedPython;
            options { language = Python3; }
            start : EOF { ProjectHelper.execute(); } ;
            """
                .trimIndent(),
            StandardCharsets.UTF_16LE,
        )

        val language =
            GrammarLanguageDetector.detectTargetLanguage(
                grammar,
                StandardCharsets.UTF_16LE,
            )
        val references =
            GrammarActionAnalyzer.analyzeGrammarFile(
                grammar,
                StandardCharsets.UTF_16LE,
            )

        assertEquals(GrammarLanguageDetector.TargetLanguage.PYTHON, language.detectedLanguage)
        assertTrue("ProjectHelper" in references.referencedClasses)
    }
}
