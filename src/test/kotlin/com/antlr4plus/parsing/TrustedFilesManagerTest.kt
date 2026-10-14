package com.antlr4plus.parsing

import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File
import java.nio.file.Files

class TrustedFilesManagerTest : BasePlatformTestCase() {

    fun `test packaged class files are marked and verified recursively`() {
        val manager = TrustedFilesManager.getInstance(project)
        val compileRoot = projectFile("compiled")
        val packagedClass = projectFile("compiled/example/parser/DemoParser.class", "trusted bytecode")

        val marked = manager.markClassFilesAsGenerated(compileRoot)

        assertEquals(listOf(packagedClass.canonicalFile), marked.map { it.canonicalFile })
        val result = manager.checkClassFiles(compileRoot)
        assertTrue(
            "Packaged class file was ignored: $result",
            result is TrustedFilesManager.ClassFileCheckResult.Valid,
        )
    }

    fun `test unmarked packaged classes cannot hide behind a trusted root class`() {
        val manager = TrustedFilesManager.getInstance(project)
        val compileRoot = projectFile("mixed-compiled")
        val rootClass = projectFile("mixed-compiled/Root.class", "trusted root bytecode")
        projectFile("mixed-compiled/example/Injected.class", "unmarked nested bytecode")

        manager.markAsGenerated(rootClass)

        val result = manager.checkClassFiles(compileRoot)
        assertTrue(
            "Unmarked packaged class was ignored: $result",
            result is TrustedFilesManager.ClassFileCheckResult.UnmarkedFiles,
        )
        val unmarked = (result as TrustedFilesManager.ClassFileCheckResult.UnmarkedFiles).files
        assertTrue(
            "Missing nested class from diagnostics: $unmarked",
            unmarked.any { it.endsWith("example/Injected.class") },
        )
    }

    fun `test tampered packaged classes cannot hide behind a trusted root class`() {
        val manager = TrustedFilesManager.getInstance(project)
        val compileRoot = projectFile("tampered-compiled")
        val rootClass = projectFile("tampered-compiled/Root.class", "trusted root bytecode")
        val packagedClass = projectFile("tampered-compiled/example/Demo.class", "trusted nested bytecode")
        manager.markAsGenerated(listOf(rootClass, packagedClass))
        packagedClass.writeText("tampered nested bytecode")

        val result = manager.checkClassFiles(compileRoot)
        assertTrue(
            "Tampered packaged class was ignored: $result",
            result is TrustedFilesManager.ClassFileCheckResult.TamperedFiles,
        )
        val tampered = (result as TrustedFilesManager.ClassFileCheckResult.TamperedFiles).files
        assertTrue(
            "Missing nested class from diagnostics: $tampered",
            tampered.any { it.endsWith("example/Demo.class") },
        )
    }

    fun `test class inventory rejects a root beneath a symbolic link ancestor`() {
        val manager = TrustedFilesManager.getInstance(project)
        val outsideRoot = projectFile("outside-workspace")
        val outsideClass = projectFile("outside-workspace/classes/Injected.class", "outside bytecode")
        val linkedWorkspace = File(requireNotNull(project.basePath), "linked-workspace").toPath()
        Files.createSymbolicLink(linkedWorkspace, outsideRoot.toPath())
        val linkedClassRoot = linkedWorkspace.resolve("classes").toFile()

        val marked = manager.markClassFilesAsGenerated(linkedClassRoot)

        assertTrue(
            "External classes were marked through a linked ancestor: $marked",
            marked.isEmpty(),
        )
        assertFalse(
            "External class received generated trust",
            manager.isGeneratedByUs(outsideClass),
        )
        assertTrue(
            "Unsafe inventory root was accepted",
            manager.checkClassFiles(linkedClassRoot) is TrustedFilesManager.ClassFileCheckResult.NoFiles,
        )
    }

    fun `test direct generated marker rejects a symbolic link class file`() {
        val manager = TrustedFilesManager.getInstance(project)
        val outsideClass = projectFile("outside-link-target/Injected.class", "outside bytecode")
        val linkedClass = File(requireNotNull(project.basePath), "Linked.class").toPath()
        Files.createSymbolicLink(linkedClass, outsideClass.toPath())

        manager.markAsGenerated(linkedClass.toFile())

        assertFalse(
            "Symbolic-link target received generated trust",
            manager.isGeneratedByUs(outsideClass),
        )
        assertFalse(
            "Symbolic-link class was accepted as generated",
            manager.isGeneratedByUs(linkedClass.toFile()),
        )
    }

    fun `test cached generated marker remains a presentation hint while full check detects changes`() {
        val manager = TrustedFilesManager.getInstance(project)
        val generatedClass = projectFile("cached-marker/Generated.class", "original bytecode")
        manager.markAsGenerated(generatedClass)
        val virtualFile =
            requireNotNull(
                LocalFileSystem.getInstance().refreshAndFindFileByIoFile(generatedClass),
            )

        generatedClass.writeText("changed bytecode")

        assertTrue(manager.isMarkedGeneratedCached(virtualFile))
        assertFalse(
            "Full generated-file verification ignored changed content",
            manager.isGeneratedByUs(generatedClass),
        )
    }

    private fun projectFile(relativePath: String, content: String? = null): File {
        val file = File(requireNotNull(project.basePath), relativePath)
        if (content == null) {
            assertTrue(file.mkdirs() || file.isDirectory)
        } else {
            assertTrue(file.parentFile.mkdirs() || file.parentFile.isDirectory)
            file.writeText(content)
        }
        return file
    }
}
