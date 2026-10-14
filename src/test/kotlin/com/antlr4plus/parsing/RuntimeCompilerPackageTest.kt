package com.antlr4plus.parsing

import com.antlr4plus.util.GeneratedWorkspaceFileOps
import java.nio.file.Files
import junit.framework.TestCase

class RuntimeCompilerPackageTest : TestCase() {

    fun `test Java compilation preserves packages and package-local collaboration`() {
        val root = Files.createTempDirectory("antlr4plus-runtime-package-test")
        try {
            val sourceRoot = Files.createDirectories(root.resolve("source"))
            val helper = sourceRoot.resolve("PackageHelper.java")
            val consumer = sourceRoot.resolve("PackageConsumer.java")
            Files.writeString(
                helper,
                """
                package example.preview;
                final class PackageHelper {
                    static String value() { return "packaged"; }
                }
                """
                    .trimIndent(),
            )
            Files.writeString(
                consumer,
                """
                package example.preview;
                public final class PackageConsumer {
                    public String value() { return PackageHelper.value(); }
                }
                """
                    .trimIndent(),
            )
            val output = root.resolve("classes")

            val result =
                RuntimeCompiler.compile(
                    listOf(helper.toFile(), consumer.toFile()),
                    emptyList(),
                    output.toFile(),
                )

            assertTrue(
                "Packaged Java compilation failed: $result",
                result is RuntimeCompiler.CompilationResult.Success,
            )
            result as RuntimeCompiler.CompilationResult.Success
            result.classLoader.use { loader ->
                val consumerClass = loader.loadClass("example.preview.PackageConsumer")
                val instance = consumerClass.getDeclaredConstructor().newInstance()
                assertEquals("packaged", consumerClass.getMethod("value").invoke(instance))
            }
            assertTrue(Files.isRegularFile(output.resolve("example/preview/PackageHelper.class")))
            assertTrue(Files.isRegularFile(output.resolve("example/preview/PackageConsumer.class")))
            assertFalse(
                "Package declaration was stripped",
                Files.exists(output.resolve("PackageConsumer.class")),
            )
        } finally {
            GeneratedWorkspaceFileOps.deleteTree(root)
        }
    }
}
