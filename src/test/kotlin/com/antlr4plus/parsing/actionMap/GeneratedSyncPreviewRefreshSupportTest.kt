package com.antlr4plus.parsing.actionMap

import com.antlr4plus.filebinding.FileBindingService
import com.antlr4plus.previewService.PreviewService
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

class GeneratedSyncPreviewRefreshSupportTest : BasePlatformTestCase() {
    private val temporaryRoots = mutableListOf<File>()

    override fun tearDown() {
        try {
            temporaryRoots.forEach(File::deleteRecursively)
            temporaryRoots.clear()
        } finally {
            super.tearDown()
        }
    }

    fun `test pending refresh target prefers the explicitly recorded g4p binding`() {
        val fixture = createBoundFixture("PreferredBindingDemo")
        val mapService = project.getService(ActionLocationMapService::class.java)

        mapService.markGeneratedSyncPending(fixture.grammarFile, fixture.secondaryG4pVFile.path)

        assertEquals(
            fixture.secondaryG4pVFile.path,
            GeneratedSyncPreviewRefreshSupport.resolvePendingRefreshTarget(
                    project,
                    fixture.grammarVFile,
                )
                ?.path,
        )
        assertEquals(
            fixture.secondaryG4pVFile.path,
            GeneratedSyncPreviewRefreshSupport.resolvePendingRefreshTargetFast(
                    project,
                    fixture.grammarVFile,
                )
                ?.path,
        )
    }

    fun `test pending refresh target falls back to the active preview binding when preferred path is stale`() {
        val fixture = createBoundFixture("ActiveBindingDemo")
        val mapService = project.getService(ActionLocationMapService::class.java)
        val previewService = project.getService(PreviewService::class.java)

        previewService.state.g4pInputFile = fixture.primaryG4pVFile
        previewService.state.grammarFile = fixture.grammarVFile
        previewService.state.lexerGrammarFile = null
        mapService.markGeneratedSyncPending(fixture.grammarFile, "/missing/does-not-exist.g4p")

        assertEquals(
            fixture.primaryG4pVFile.path,
            GeneratedSyncPreviewRefreshSupport.resolvePendingRefreshTargetFast(
                    project,
                    fixture.grammarVFile,
                )
                ?.path,
        )
        assertEquals(
            fixture.primaryG4pVFile.path,
            GeneratedSyncPreviewRefreshSupport.resolvePendingRefreshTarget(
                    project,
                    fixture.grammarVFile,
                )
                ?.path,
        )
    }

    fun `test preview owner falls back to matching bound profile outside active preview`() {
        val fixture = createDistinctProfileFixture("BoundOwnerDemo")
        val previewService = project.getService(PreviewService::class.java)

        previewService.state.g4pInputFile = fixture.primaryG4pVFile
        previewService.state.grammarFile = fixture.grammarVFile
        previewService.state.lexerGrammarFile = null

        val owner =
            GeneratedSyncPreviewRefreshSupport.resolvePreviewOwner(
                project = project,
                generatedFile = fixture.secondaryGeneratedFile,
                grammarFileHint = fixture.grammarFile,
            )

        assertNotNull(owner)
        assertEquals(fixture.secondaryG4pVFile.path, owner!!.g4pFile.path)
        assertEquals(fixture.grammarVFile.path, owner.grammarFile.path)
    }

    private fun createBoundFixture(name: String): BoundFixture {
        val root = createTemporaryRoot("generated-sync-target-$name-")

        val grammarFile = File(root, "$name.g4")
        val primaryG4pFile = File(root, "${name}One.g4p")
        val secondaryG4pFile = File(root, "${name}Two.g4p")

        grammarFile.writeText(
            """
            grammar $name;

            rule : ID EOF ;
            ID : [a-zA-Z]+ ;
            WS : [ \t\r\n]+ -> skip ;
            """
                .trimIndent(),
        )
        primaryG4pFile.writeText("input one")
        secondaryG4pFile.writeText("input two")

        val grammarVFile = refreshFile(grammarFile)
        val primaryG4pVFile = refreshFile(primaryG4pFile)
        val secondaryG4pVFile = refreshFile(secondaryG4pFile)
        assertNotNull(grammarVFile)
        assertNotNull(primaryG4pVFile)
        assertNotNull(secondaryG4pVFile)

        val bindingService = project.getService(FileBindingService::class.java)
        assertTrue(bindingService.bind(primaryG4pVFile!!, grammarVFile!!))
        assertTrue(bindingService.bind(secondaryG4pVFile!!, grammarVFile))

        return BoundFixture(
            grammarFile = grammarFile,
            grammarVFile = grammarVFile,
            primaryG4pVFile = primaryG4pVFile,
            secondaryG4pVFile = secondaryG4pVFile,
        )
    }

    private fun createDistinctProfileFixture(name: String): DistinctProfileFixture {
        val root = createTemporaryRoot("generated-sync-owner-$name-")
        val primaryDir = File(root, "primary")
        val secondaryDir = File(root, "secondary")
        primaryDir.mkdirs()
        secondaryDir.mkdirs()

        val grammarFile = File(root, "$name.g4")
        val primaryG4pFile = File(primaryDir, "${name}One.g4p")
        val secondaryG4pFile = File(secondaryDir, "${name}Two.g4p")
        val secondaryGeneratedFile =
            File(
                secondaryDir,
                "gen/preview/java/${name}Parser.java",
            )

        grammarFile.writeText(
            """
            grammar $name;

            rule : ID EOF ;
            ID : [a-zA-Z]+ ;
            WS : [ \t\r\n]+ -> skip ;
            """
                .trimIndent(),
        )
        primaryG4pFile.writeText("input one")
        secondaryG4pFile.writeText("input two")
        secondaryGeneratedFile.parentFile.mkdirs()
        secondaryGeneratedFile.writeText(
            """
            public class ${name}Parser extends Parser {}
            """
                .trimIndent(),
        )

        val grammarVFile = refreshFile(grammarFile)
        val primaryG4pVFile = refreshFile(primaryG4pFile)
        val secondaryG4pVFile = refreshFile(secondaryG4pFile)
        assertNotNull(grammarVFile)
        assertNotNull(primaryG4pVFile)
        assertNotNull(secondaryG4pVFile)

        val bindingService = project.getService(FileBindingService::class.java)
        assertTrue(bindingService.bind(primaryG4pVFile!!, grammarVFile!!))
        assertTrue(bindingService.bind(secondaryG4pVFile!!, grammarVFile))

        return DistinctProfileFixture(
            grammarFile = grammarFile,
            grammarVFile = grammarVFile,
            primaryG4pVFile = primaryG4pVFile,
            secondaryG4pVFile = secondaryG4pVFile,
            secondaryGeneratedFile = secondaryGeneratedFile,
        )
    }

    private fun refreshFile(file: File): VirtualFile? = runWriteAction {
        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)
    }

    private fun createTemporaryRoot(prefix: String): File =
        File(project.basePath!!, "$prefix${System.nanoTime()}").also {
            assertTrue(it.mkdirs())
            temporaryRoots += it
        }

    private data class BoundFixture(
        val grammarFile: File,
        val grammarVFile: VirtualFile,
        val primaryG4pVFile: VirtualFile,
        val secondaryG4pVFile: VirtualFile,
    )

    private data class DistinctProfileFixture(
        val grammarFile: File,
        val grammarVFile: VirtualFile,
        val primaryG4pVFile: VirtualFile,
        val secondaryG4pVFile: VirtualFile,
        val secondaryGeneratedFile: File,
    )
}
