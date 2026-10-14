package com.antlr4plus.parsing

import com.antlr4plus.util.GeneratedWorkspaceFileOps
import java.nio.file.Files
import java.nio.file.Path
import junit.framework.TestCase

class RuntimeCompilerSourceDiscoveryTest : TestCase() {

    fun `test Java source discovery never follows symbolic links`() {
        withSourceTree { root, scanRoot, outsideRoot ->
            val inside = writeSource(scanRoot.resolve("nested/Inside.java"), "class Inside {}")
            val outside = writeSource(outsideRoot.resolve("Outside.java"), "class Outside {}")
            Files.createSymbolicLink(scanRoot.resolve("linked-directory"), outsideRoot)
            Files.createSymbolicLink(scanRoot.resolve("Linked.java"), outside)
            val linkedRoot = root.resolve("linked-root")
            Files.createSymbolicLink(linkedRoot, outsideRoot)

            assertEquals(
                setOf(FileKey.of(inside.toFile())),
                RuntimeCompiler.findJavaFiles(scanRoot.toFile()).map(FileKey::of).toSet(),
            )
            assertTrue(RuntimeCompiler.findJavaFiles(linkedRoot.toFile()).isEmpty())
        }
    }

    fun `test Kotlin source discovery never follows symbolic links`() {
        withSourceTree { root, scanRoot, outsideRoot ->
            val inside = writeSource(scanRoot.resolve("nested/Inside.kt"), "class Inside")
            val outside = writeSource(outsideRoot.resolve("Outside.kt"), "class Outside")
            Files.createSymbolicLink(scanRoot.resolve("linked-directory"), outsideRoot)
            Files.createSymbolicLink(scanRoot.resolve("Linked.kt"), outside)
            val linkedRoot = root.resolve("linked-root")
            Files.createSymbolicLink(linkedRoot, outsideRoot)

            assertEquals(
                setOf(FileKey.of(inside.toFile())),
                KotlinRuntimeCompiler.findKotlinFiles(scanRoot.toFile()).map(FileKey::of).toSet(),
            )
            assertTrue(KotlinRuntimeCompiler.findKotlinFiles(linkedRoot.toFile()).isEmpty())
        }
    }

    fun `test source discovery rejects files and missing roots`() {
        withSourceTree { root, scanRoot, _ ->
            val file = writeSource(scanRoot.resolve("Only.java"), "class Only {}")

            assertTrue(RuntimeCompiler.findJavaFiles(file.toFile()).isEmpty())
            assertTrue(KotlinRuntimeCompiler.findKotlinFiles(file.toFile()).isEmpty())
            assertTrue(RuntimeCompiler.findJavaFiles(root.resolve("missing").toFile()).isEmpty())
            assertTrue(
                KotlinRuntimeCompiler.findKotlinFiles(root.resolve("missing").toFile()).isEmpty(),
            )
        }
    }

    fun `test source discovery rejects roots beneath symbolic link ancestors`() {
        withSourceTree { root, _, outsideRoot ->
            val nestedOutside = Files.createDirectories(outsideRoot.resolve("nested"))
            writeSource(nestedOutside.resolve("Outside.java"), "class Outside {}")
            writeSource(nestedOutside.resolve("Outside.kt"), "class Outside")
            val linkedRoot = root.resolve("linked-root")
            Files.createSymbolicLink(linkedRoot, outsideRoot)
            val scanRootBeneathLink = linkedRoot.resolve("nested")

            assertTrue(RuntimeCompiler.findJavaFiles(scanRootBeneathLink.toFile()).isEmpty())
            assertTrue(
                KotlinRuntimeCompiler.findKotlinFiles(scanRootBeneathLink.toFile()).isEmpty(),
            )
        }
    }

    private fun withSourceTree(action: (Path, Path, Path) -> Unit) {
        val root = Files.createTempDirectory("antlr4plus-source-discovery-test")
        try {
            val scanRoot = Files.createDirectories(root.resolve("generated"))
            val outsideRoot = Files.createDirectories(root.resolve("outside"))
            action(root, scanRoot, outsideRoot)
        } finally {
            GeneratedWorkspaceFileOps.deleteTree(root)
        }
    }

    private fun writeSource(path: Path, content: String): Path {
        Files.createDirectories(path.parent)
        Files.writeString(path, content)
        return path.toAbsolutePath().normalize()
    }

    private data class FileKey(val path: Path) {
        companion object {
            fun of(file: java.io.File): FileKey = FileKey(file.toPath().toAbsolutePath().normalize())
        }
    }
}
