package com.antlr4plus.parsing

import com.antlr4plus.util.GeneratedWorkspaceFileOps
import java.nio.file.Files
import java.nio.file.Path
import junit.framework.TestCase

class RuntimeCompilerFilesystemSafetyTest : TestCase() {

    fun `test compilation rejects a symbolic link output directory`() {
        withCompilerTree { root ->
            val source = writeSource(root.resolve("source/Hello.java"), "public class Hello {}")
            val outside = Files.createDirectories(root.resolve("outside-output"))
            val linkedOutput = root.resolve("linked-output")
            Files.createSymbolicLink(linkedOutput, outside)

            val result = RuntimeCompiler.compile(listOf(source.toFile()), emptyList(), linkedOutput.toFile())
            closeIfSuccessful(result)

            assertTrue(
                "Compiler wrote through a symbolic-link output root: $result",
                result is RuntimeCompiler.CompilationResult.Failure,
            )
            assertTrue(
                "Compiler modified the linked target",
                Files.list(outside).use { it.findAny().isEmpty },
            )
        }
    }

    fun `test compilation rejects symbolic link source files`() {
        withCompilerTree { root ->
            val outsideSource = writeSource(root.resolve("outside/Evil.java"), "public class Evil {}")
            val linkedSource = root.resolve("source/Evil.java")
            Files.createDirectories(linkedSource.parent)
            Files.createSymbolicLink(linkedSource, outsideSource)
            val output = root.resolve("classes")

            val result = RuntimeCompiler.compile(listOf(linkedSource.toFile()), emptyList(), output.toFile())
            closeIfSuccessful(result)

            assertTrue(
                "Compiler accepted a symbolic-link source: $result",
                result is RuntimeCompiler.CompilationResult.Failure,
            )
            assertFalse("Linked source was compiled", Files.exists(output.resolve("Evil.class")))
        }
    }

    fun `test compilation rejects an output beneath a symbolic link ancestor`() {
        withCompilerTree { root ->
            val source = writeSource(root.resolve("source/Hello.java"), "public class Hello {}")
            val outside = Files.createDirectories(root.resolve("outside-workspace"))
            val linkedWorkspace = root.resolve("linked-workspace")
            Files.createSymbolicLink(linkedWorkspace, outside)
            val output = linkedWorkspace.resolve("classes")

            val result = RuntimeCompiler.compile(listOf(source.toFile()), emptyList(), output.toFile())
            closeIfSuccessful(result)

            assertTrue(
                "Compiler wrote beneath a symbolic-link ancestor: $result",
                result is RuntimeCompiler.CompilationResult.Failure,
            )
            assertFalse(
                "Compiler created output in the linked target",
                Files.exists(outside.resolve("classes")),
            )
        }
    }

    fun `test compilation rejects a source beneath a symbolic link ancestor`() {
        withCompilerTree { root ->
            val outside = Files.createDirectories(root.resolve("outside-source"))
            writeSource(outside.resolve("Evil.java"), "public class Evil {}")
            val linkedSourceRoot = root.resolve("linked-source")
            Files.createSymbolicLink(linkedSourceRoot, outside)
            val linkedSource = linkedSourceRoot.resolve("Evil.java")
            val output = root.resolve("classes")

            val result = RuntimeCompiler.compile(listOf(linkedSource.toFile()), emptyList(), output.toFile())
            closeIfSuccessful(result)

            assertTrue(
                "Compiler accepted a source beneath a symbolic-link ancestor: $result",
                result is RuntimeCompiler.CompilationResult.Failure,
            )
            assertFalse("Linked source was compiled", Files.exists(output.resolve("Evil.class")))
        }
    }

    fun `test compilation reports missing sources as a structured failure`() {
        withCompilerTree { root ->
            val result =
                RuntimeCompiler.compile(
                    listOf(root.resolve("missing/Missing.java").toFile()),
                    emptyList(),
                    root.resolve("classes").toFile(),
                )
            closeIfSuccessful(result)

            assertTrue(
                "Missing source escaped or compiled: $result",
                result is RuntimeCompiler.CompilationResult.Failure,
            )
            val errors = (result as RuntimeCompiler.CompilationResult.Failure).errors
            assertTrue(
                "Failure did not identify the missing source: $errors",
                errors.any { it.contains("Missing.java") },
            )
        }
    }

    private fun withCompilerTree(action: (Path) -> Unit) {
        val root = Files.createTempDirectory("antlr4plus-runtime-compiler-test")
        try {
            action(root)
        } finally {
            GeneratedWorkspaceFileOps.deleteTree(root)
        }
    }

    private fun writeSource(path: Path, content: String): Path {
        Files.createDirectories(path.parent)
        Files.writeString(path, content)
        return path
    }

    private fun closeIfSuccessful(result: RuntimeCompiler.CompilationResult) {
        if (result is RuntimeCompiler.CompilationResult.Success) {
            result.classLoader.close()
        }
    }
}
