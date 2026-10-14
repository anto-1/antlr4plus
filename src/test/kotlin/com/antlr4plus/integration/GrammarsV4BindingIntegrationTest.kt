package com.antlr4plus.integration

import com.antlr4plus.filebinding.BindingOperations
import com.antlr4plus.filebinding.FileBindingService
import com.antlr4plus.onboarding.GrammarsV4Bindings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** Exercises xattr bindings with files copied from grammars-v4. */
class GrammarsV4BindingIntegrationTest : BasePlatformTestCase() {

    fun `test representative grammars-v4 inputs bind in a temporary project`() {
        val fixtureRoot = fixtureRoot()
        val allBindings = BindingOperations.parseBindings(GrammarsV4Bindings.BINDINGS)
        val requestedGrammarPaths =
            setOf(
                "awk/awk.g4",
                "java/java/JavaParser.g4",
                "json/JSON.g4",
            )
        val selectedBindings = requestedGrammarPaths.map { grammarPath ->
            allBindings.first { binding ->
                binding.grammarPath == grammarPath && Files.isRegularFile(fixtureInput(fixtureRoot, binding.g4pPath))
            }
        }
        val bindingService = project.getService(FileBindingService::class.java)

        selectedBindings.forEachIndexed { index, binding ->
            // Use disk-backed files: light-fixture temp:// files have no NIO path or xattr support.
            val prefix = "grammars-v4-binding-$index-${System.nanoTime()}"
            val grammarRelativePath = "$prefix/${binding.grammarPath}"
            val profileRelativePath = "$prefix/${binding.g4pPath}"
            val grammarPath = File(project.basePath!!, grammarRelativePath).toPath()
            val profilePath = File(project.basePath!!, profileRelativePath).toPath()
            Files.createDirectories(grammarPath.parent)
            Files.createDirectories(profilePath.parent)
            Files.copy(fixtureRoot.resolve(binding.grammarPath), grammarPath)
            Files.copy(fixtureInput(fixtureRoot, binding.g4pPath), profilePath)

            val grammarFile = runWriteAction {
                LocalFileSystem.getInstance().refreshAndFindFileByNioFile(grammarPath)
            }
            val profileFile = runWriteAction {
                LocalFileSystem.getInstance().refreshAndFindFileByNioFile(profilePath)
            }
            assertNotNull(grammarFile)
            assertNotNull(profileFile)

            val result =
                ApplicationManager.getApplication()
                    .executeOnPooledThread<BindingResult> {
                        val bound = bindingService.bind(profileFile!!, grammarFile!!)
                        BindingResult(
                            bound = bound,
                            storedPath = bindingService.getBoundGrammarPath(profileFile),
                            resolvedGrammar = bindingService.getBoundGrammar(profileFile),
                            broken = bindingService.isBindingBroken(profileFile),
                        )
                    }
                    .get()

            assertTrue(result.bound)
            assertEquals(grammarRelativePath, result.storedPath)
            assertEquals(grammarFile, result.resolvedGrammar)
            assertFalse(result.broken)
        }
    }

    private fun fixtureInput(root: Path, profilePath: String): Path =
        root.resolve(profilePath).takeIf { Files.isRegularFile(it) } ?: root.resolve(profilePath.removeSuffix(".g4p"))

    private fun fixtureRoot(): Path {
        val configured =
            System.getProperty("grammarsV4Dir")
                ?: error("grammarsV4Dir was not supplied by the grammarsV4IntegrationTest task")
        val root = Paths.get(configured).toAbsolutePath().normalize()
        assertTrue("grammars-v4 fixture is not a directory: $root", Files.isDirectory(root))
        return root
    }

    private data class BindingResult(
        val bound: Boolean,
        val storedPath: String?,
        val resolvedGrammar: com.intellij.openapi.vfs.VirtualFile?,
        val broken: Boolean,
    )
}
