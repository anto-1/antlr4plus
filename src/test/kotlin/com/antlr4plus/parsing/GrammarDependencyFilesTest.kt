package com.antlr4plus.parsing

import java.io.File
import java.nio.file.Files
import junit.framework.TestCase
import org.antlr.v4.tool.Grammar
import org.antlr.v4.tool.LexerGrammar

class GrammarDependencyFilesTest : TestCase() {

    fun `test direct parser lexer and imported grammars are refresh dependencies`() {
        val root = Files.createTempDirectory("antlr4plus-grammar-dependencies-").toFile()
        try {
            val parserFile = File(root, "DemoParser.g4")
            val lexerFile = File(root, "DemoLexer.g4")
            val delegateFile = File(root, "Delegate.g4")
            val parser = grammar("parser grammar DemoParser; start: delegated EOF;", parserFile)
            val delegate = grammar("parser grammar Delegate; delegated: ID;", delegateFile)
            parser.importedGrammars = mutableListOf(delegate)

            assertTrue(isDependency(parserFile, parserFile, lexerFile, parser))
            assertTrue(isDependency(lexerFile, parserFile, lexerFile, parser))
            assertTrue(isDependency(delegateFile, parserFile, lexerFile, parser))
            assertFalse(isDependency(File(root, "Unrelated.g4"), parserFile, lexerFile, parser))
        } finally {
            root.deleteRecursively()
        }
    }

    fun `test transitive imported grammar is a refresh dependency`() {
        val root = Files.createTempDirectory("antlr4plus-transitive-grammar-").toFile()
        try {
            val parserFile = File(root, "Root.g4")
            val firstFile = File(root, "First.g4")
            val secondFile = File(root, "Second.g4")
            val parser = grammar("parser grammar Root; start: first EOF;", parserFile)
            val first = grammar("parser grammar First; first: second;", firstFile)
            val second = grammar("parser grammar Second; second: 'ok';", secondFile)
            first.importedGrammars = mutableListOf(second)
            parser.importedGrammars = mutableListOf(first)

            assertTrue(isDependency(secondFile, parserFile, null, parser))
        } finally {
            root.deleteRecursively()
        }
    }

    fun `test transitive relative import resolves from importing grammar directory`() {
        val root = Files.createTempDirectory("antlr4plus-nested-relative-grammar-").toFile()
        try {
            val parserFile = File(root, "Root.g4")
            val firstFile = File(root, "delegates/First.g4")
            val secondFile = File(root, "delegates/Second.g4")
            val parser = grammar("parser grammar Root; start: first EOF;", parserFile)
            val first = grammar("parser grammar First; first: second;", File("delegates/First.g4"))
            val second = grammar("parser grammar Second; second: 'ok';", File("Second.g4"))
            first.importedGrammars = mutableListOf(second)
            parser.importedGrammars = mutableListOf(first)

            assertTrue(isDependency(firstFile, parserFile, null, parser))
            assertTrue(isDependency(secondFile, parserFile, null, parser))
            assertFalse(isDependency(File(root, "Second.g4"), parserFile, null, parser))
        } finally {
            root.deleteRecursively()
        }
    }

    fun `test relative imported file names resolve from primary grammar directory`() {
        val root = Files.createTempDirectory("antlr4plus-relative-grammar-").toFile()
        try {
            val parserFile = File(root, "Root.g4")
            val delegateFile = File(root, "Delegate.g4")
            val parser = grammar("parser grammar Root; start: delegated EOF;", parserFile)
            val delegate = grammar("parser grammar Delegate; delegated: 'ok';", File("Delegate.g4"))
            parser.importedGrammars = mutableListOf(delegate)

            assertTrue(isDependency(delegateFile, parserFile, null, parser))
        } finally {
            root.deleteRecursively()
        }
    }

    fun `test sibling directory with common prefix is not a refresh dependency`() {
        val workspace = Files.createTempDirectory("antlr4plus-grammar-prefix-").toFile()
        try {
            val project = File(workspace, "project")
            val sibling = File(workspace, "project-copy")
            val parserFile = File(project, "Root.g4")
            val delegateFile = File(project, "Delegate.g4")
            val parser = grammar("parser grammar Root; start: delegated EOF;", parserFile)
            parser.importedGrammars =
                mutableListOf(
                    grammar("parser grammar Delegate; delegated: 'ok';", delegateFile),
                )

            assertFalse(
                isDependency(File(sibling, "Delegate.g4"), parserFile, null, parser),
            )
        } finally {
            workspace.deleteRecursively()
        }
    }

    fun `test synthetic implicit lexer name is not treated as a disk dependency`() {
        val root = Files.createTempDirectory("antlr4plus-synthetic-lexer-").toFile()
        try {
            val parserFile = File(root, "Combined.g4")
            val parser = grammar("grammar Combined; start: EOF;", parserFile)
            val syntheticLexer =
                LexerGrammar("lexer grammar CombinedLexer; TOKEN: 'x';").also {
                    it.fileName = "<string>"
                }

            val dependencies =
                GrammarDependencyFiles.collect(
                    parserFile,
                    null,
                    parser,
                    syntheticLexer,
                )

            assertEquals(
                listOf(parserFile.toPath().toAbsolutePath().normalize()),
                dependencies.map { it.toPath() },
            )
        } finally {
            root.deleteRecursively()
        }
    }

    private fun isDependency(
        changedFile: File,
        parserFile: File,
        lexerFile: File?,
        parser: Grammar,
    ): Boolean =
        GrammarDependencyFiles.contains(
            changedFile,
            parserFile,
            lexerFile,
            parser,
            null,
        )

    private fun grammar(source: String, file: File): Grammar =
        Grammar(source).also {
            it.fileName = file.path
        }
}
