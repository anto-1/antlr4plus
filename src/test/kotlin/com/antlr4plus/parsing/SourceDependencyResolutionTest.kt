package com.antlr4plus.parsing

import com.antlr4plus.previewService.PreviewService
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.fixtures.TempDirTestFixture
import com.intellij.testFramework.fixtures.impl.TempDirTestFixtureImpl
import kotlinx.coroutines.runBlocking
import org.antlr.v4.tool.Grammar

class SourceDependencyResolutionTest : BasePlatformTestCase() {
    override fun createTempDirTestFixture(): TempDirTestFixture = TempDirTestFixtureImpl()

    override fun tearDown() {
        try {
            CompiledParsingService.clearAllSessionState()
        } finally {
            super.tearDown()
        }
    }

    fun `test grammar action support files include transitive dependencies and terminate cycles`() = runBlocking {
        assertResolvedSources(
            "@members { FixtureSupport helper = FixtureSupport.create(); }",
            "FixtureSupport.java",
            "FixtureLeaf.kt",
        )
    }

    fun `test base class discovery keeps nearby and transitive sources without false conflicts`() = runBlocking {
        myFixture.addFileToProject(
            "grammars/base/FixtureBase.java",
            "public class FixtureBase { FixtureSupport helper = new FixtureSupport(); }",
        )
        assertResolvedSources(
            "options { superClass=FixtureBase; }",
            "FixtureBase.java",
            "FixtureSupport.java",
            "FixtureLeaf.kt",
        )
    }

    fun `test constructor-only grammar actions resolve support files and their dependencies`() = runBlocking {
        assertResolvedSources(
            "@members { FixtureSupport helper = new FixtureSupport(); }",
            "FixtureSupport.java",
            "FixtureLeaf.kt",
        )
    }

    private suspend fun assertResolvedSources(declaration: String, vararg expectedFiles: String) {
        val profile = myFixture.addFileToProject("input.g4p", "")
        PsiTestUtil.addContentRoot(module, profile.virtualFile.parent)
        val preview = project.getService(PreviewService::class.java)
        preview.state.g4pInputFile = profile.virtualFile
        myFixture.addFileToProject(
            "grammars/support/FixtureSupport.java",
            """
            public class FixtureSupport {
                FixtureLeaf leaf = new FixtureLeaf();
                public static FixtureSupport create() { return new FixtureSupport(); }
            }
            """
                .trimIndent(),
        )
        myFixture.addFileToProject(
            "grammars/leaf/FixtureLeaf.kt",
            "class FixtureLeaf { val parent: FixtureSupport? = null }",
        )
        val grammarText = "grammar DependencyFixture; $declaration start: EOF;"
        val grammarFile = myFixture.addFileToProject("grammars/DependencyFixture.g4", grammarText)
        val result =
            requireNotNull(
                CompiledParsingService.preResolveExternalJavaFiles(
                    grammarFile.virtualFile,
                    null,
                    Grammar(grammarText),
                    project,
                    preview,
                ),
            )

        assertEquals(expectedFiles.toSet(), result.files.map { it.name }.toSet())
        assertEquals(expectedFiles.size, result.files.size)
        assertFalse(result.hadConflictResolution)
    }
}
