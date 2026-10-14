package com.antlr4plus.parsing

import com.antlr4plus.util.GeneratedWorkspaceFileOps
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import junit.framework.TestCase

class KotlinRuntimeCompilerTempHomeTest : TestCase() {

    fun `test compiler home initialization ignores a predictable symbolic link`() {
        val root = Files.createTempDirectory("antlr4plus-kotlin-home-test")
        val outside = Files.createDirectories(root.resolve("outside"))
        val predictableHome = root.resolve("kotlin-compiler-home")
        Files.createSymbolicLink(predictableHome, outside)

        val originalTmpDir = System.getProperty("java.io.tmpdir")
        val previousHomePath = getCompilerField("fakeIdeaHomePath")
        val previousInitialized = getCompilerField("fakeIdeaHomeInitialized")
        var createdHome: File? = null

        try {
            System.setProperty("java.io.tmpdir", root.toString())
            setCompilerField("fakeIdeaHomePath", null)
            setCompilerField("fakeIdeaHomeInitialized", false)

            createdHome = invokeSetupFakeIdeaHome()

            assertFalse(
                "Compiler accepted the predictable symbolic link",
                Files.isSymbolicLink(createdHome.toPath()),
            )
            assertTrue(
                "Compiler home is not an owned directory: $createdHome",
                Files.isDirectory(createdHome.toPath(), LinkOption.NOFOLLOW_LINKS),
            )
            assertFalse(
                "Compiler reused the predictable path",
                createdHome.toPath() == predictableHome,
            )
            assertTrue(
                "Compiler home was not initialized",
                Files.isRegularFile(
                    createdHome.toPath().resolve("bin/idea.properties"),
                    LinkOption.NOFOLLOW_LINKS,
                ),
            )
            assertTrue(
                "Compiler modified the symbolic-link target",
                Files.list(outside).use { it.findAny().isEmpty },
            )
        } finally {
            setCompilerField("fakeIdeaHomePath", previousHomePath)
            setCompilerField("fakeIdeaHomeInitialized", previousInitialized)
            if (originalTmpDir == null) System.clearProperty("java.io.tmpdir")
            else System.setProperty("java.io.tmpdir", originalTmpDir)
            createdHome?.let { GeneratedWorkspaceFileOps.deleteTree(it.toPath()) }
            GeneratedWorkspaceFileOps.deleteTree(root)
        }
    }

    private fun invokeSetupFakeIdeaHome(): File =
        KotlinRuntimeCompiler::class.java.getDeclaredMethod("setupFakeIdeaHome").run {
            isAccessible = true
            invoke(KotlinRuntimeCompiler) as File
        }

    private fun getCompilerField(name: String): Any? =
        KotlinRuntimeCompiler::class.java.getDeclaredField(name).run {
            isAccessible = true
            get(null)
        }

    private fun setCompilerField(name: String, value: Any?) {
        KotlinRuntimeCompiler::class.java.getDeclaredField(name).run {
            isAccessible = true
            set(null, value)
        }
    }
}
