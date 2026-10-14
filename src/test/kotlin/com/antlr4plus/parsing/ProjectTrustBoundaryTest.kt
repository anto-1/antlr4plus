package com.antlr4plus.parsing

import com.antlr4plus.codeGen.GenAntlrCode
import com.antlr4plus.compat.compatIsTrusted
import com.antlr4plus.compat.compatSetTrusted
import com.antlr4plus.dotG4Support.validation.GrammarIssuesCollector
import com.antlr4plus.previewService.PreviewService
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.fixtures.TempDirTestFixture
import com.intellij.testFramework.fixtures.impl.TempDirTestFixtureImpl

class ProjectTrustBoundaryTest : BasePlatformTestCase() {
    // ANTLR reads token vocabularies through java.io.File, outside the fixture's in-memory VFS.
    override fun createTempDirTestFixture(): TempDirTestFixture = TempDirTestFixtureImpl()

    override fun setUp() {
        super.setUp()
        val profile = myFixture.addFileToProject("input.g4p", "x")
        PsiTestUtil.addContentRoot(module, profile.virtualFile.parent)
        project.getService(PreviewService::class.java).state.g4pInputFile = profile.virtualFile
        project.compatSetTrusted(true)
    }

    override fun tearDown() {
        try {
            project.compatSetTrusted(true)
            project.getService(PreviewService::class.java).state.g4pInputFile = null
        } finally {
            super.tearDown()
        }
    }

    fun `test untrusted grammar analysis does not read token vocab outside the grammar directory`() {
        myFixture.addFileToProject("outside.tokens", "PRIVATE_VOCAB_MARKER\n")
        val grammar =
            myFixture.addFileToProject(
                "grammars/Boundary.g4",
                "parser grammar Boundary; options { tokenVocab='../outside'; } start: EOF;",
            )

        project.compatSetTrusted(false)
        assertFalse(project.compatIsTrusted())
        assertEmpty(GrammarIssuesCollector.collectGrammarIssues(grammar))
        assertNull(ParsingUtils.loadGrammars(project, grammar.virtualFile))

        // The same input reaches the vocabulary reader after explicit trust.
        project.compatSetTrusted(true)
        val trustedIssues = GrammarIssuesCollector.collectGrammarIssues(grammar)
        assertTrue(trustedIssues.any { it.annotation?.contains("PRIVATE_VOCAB_MARKER") == true })
    }

    fun `test syntax diagnostics remain available without trust`() {
        val grammar = myFixture.addFileToProject("Broken.g4", "grammar Broken; start: ; invalid")
        project.compatSetTrusted(false)
        assertFalse(project.compatIsTrusted())
        assertNotEmpty(GrammarIssuesCollector.collectGrammarIssues(grammar))
    }

    fun `test supported targets do not produce unknown language warnings during analysis`() {
        val grammar = myFixture.addFileToProject("Boundary.g4", "grammar Boundary; start: 'x' EOF;")
        val settings = requireNotNull(project.getService(PreviewService::class.java).state.liveSettings)
        for (language in listOf("Java", "Kotlin", "Go")) {
            settings.language = language
            assertEmpty(GrammarIssuesCollector.collectGrammarIssues(grammar))
        }
    }

    fun `test direct generation requires trust before resolving output or launching a target`() {
        val grammar =
            myFixture.addFileToProject(
                "Boundary.g4",
                "grammar Boundary; options { language=Go; } start: 'x' EOF;",
            )
        project.compatSetTrusted(false)
        assertFalse(project.compatIsTrusted())

        val result = GenAntlrCode.antlr(project, grammar.virtualFile)
        assertFalse(result.successful)
        assertEquals("", result.outputDirectory)
        assertTrue(result.errors.any { it.contains("Trust the project") })
        assertEquals(
            setOf("Boundary.g4", "input.g4p"),
            grammar.virtualFile.parent.children.map { it.name }.toSet(),
        )
    }
}
