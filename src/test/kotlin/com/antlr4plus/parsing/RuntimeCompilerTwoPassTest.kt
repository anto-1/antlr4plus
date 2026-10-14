package com.antlr4plus.parsing

import com.antlr4plus.util.GeneratedWorkspaceFileOps
import java.nio.file.Files
import java.nio.file.Path
import junit.framework.TestCase

class RuntimeCompilerTwoPassTest : TestCase() {

    fun `test two-pass compilation resolves a packaged base class`() {
        withCompilerTree { root ->
            val base =
                writeSource(
                    root.resolve("source/PackagedBase.java"),
                    """
                    package example.twopass;
                    public class PackagedBase {
                        public String baseValue() { return "base"; }
                    }
                    """
                        .trimIndent(),
                )
            val generated =
                writeSource(
                    root.resolve("source/PackagedGenerated.java"),
                    """
                    package example.twopass;
                    public class PackagedGenerated extends PackagedBase {
                        public String value() { return baseValue() + "-generated"; }
                    }
                    """
                        .trimIndent(),
                )

            val result =
                RuntimeCompiler.compileTwoPasses(
                    generatedFiles = listOf(generated.toFile()),
                    baseClassFiles = listOf(base.toFile()),
                    classpath = emptyList(),
                    outputDir = root.resolve("classes").toFile(),
                )

            assertEquals("base-generated", invokeValue(result, "example.twopass.PackagedGenerated"))
        }
    }

    fun `test two-pass compilation falls back for circular source dependencies`() {
        withCompilerTree { root ->
            val base =
                writeSource(
                    root.resolve("source/CircularBase.java"),
                    """
                    package example.circular;
                    public class CircularBase {
                        public int value() { return CircularGenerated.VALUE; }
                    }
                    """
                        .trimIndent(),
                )
            val generated =
                writeSource(
                    root.resolve("source/CircularGenerated.java"),
                    """
                    package example.circular;
                    public class CircularGenerated extends CircularBase {
                        public static final int VALUE = 42;
                    }
                    """
                        .trimIndent(),
                )

            val result =
                RuntimeCompiler.compileTwoPasses(
                    generatedFiles = listOf(generated.toFile()),
                    baseClassFiles = listOf(base.toFile()),
                    classpath = emptyList(),
                    outputDir = root.resolve("classes").toFile(),
                )

            assertEquals(42, invokeValue(result, "example.circular.CircularGenerated"))
        }
    }

    private fun invokeValue(result: RuntimeCompiler.CompilationResult, binaryName: String): Any? {
        assertTrue(
            "Compilation failed: $result",
            result is RuntimeCompiler.CompilationResult.Success,
        )
        result as RuntimeCompiler.CompilationResult.Success
        return result.classLoader.use { loader ->
            val type = loader.loadClass(binaryName)
            type.getMethod("value").invoke(type.getDeclaredConstructor().newInstance())
        }
    }

    private fun withCompilerTree(action: (Path) -> Unit) {
        val root = Files.createTempDirectory("antlr4plus-runtime-two-pass-test")
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
}
