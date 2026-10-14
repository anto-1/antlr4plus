package com.antlr4plus.dependencies

import com.antlr4plus.util.GeneratedWorkspaceFileOps
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File
import java.nio.charset.Charset
import java.nio.file.Files

class UnifiedDependencyResolverTest : BasePlatformTestCase() {

    private lateinit var caseRoot: File

    override fun setUp() {
        super.setUp()
        val projectPath = File(requireNotNull(project.basePath)).toPath()
        Files.createDirectories(projectPath)
        caseRoot =
            Files.createTempDirectory(
                    projectPath,
                    "resolver-case-",
                )
                .toFile()
    }

    override fun tearDown() {
        try {
            if (::caseRoot.isInitialized) {
                assertTrue(GeneratedWorkspaceFileOps.deleteTree(caseRoot.toPath()))
            }
        } finally {
            super.tearDown()
        }
    }

    fun `test project boundary uses path components rather than string prefixes`() {
        val parent = Files.createTempDirectory("antlr4plus-boundary-")
        try {
            val projectDir = Files.createDirectories(parent.resolve("project"))
            val inside = Files.createDirectories(projectDir.resolve("grammars")).resolve("Demo.g4")
            val siblingPrefix = Files.createDirectories(parent.resolve("project-copy")).resolve("Outside.g4")
            Files.writeString(inside, "grammar Demo; start: EOF;")
            Files.writeString(siblingPrefix, "grammar Outside; start: EOF;")

            assertTrue(
                UnifiedDependencyResolver.isWithinBoundary(projectDir.toFile(), inside.toFile()),
            )
            assertFalse(
                UnifiedDependencyResolver.isWithinBoundary(
                    projectDir.toFile(),
                    siblingPrefix.toFile(),
                ),
            )
        } finally {
            assertTrue(GeneratedWorkspaceFileOps.deleteTree(parent))
        }
    }

    fun `test qualified dependency resolves from a standard Java source layout`() {
        val grammar = grammarWithHeader("import com.example.Helper;")
        val helper =
            projectFile(
                "src/main/java/com/example/Helper.java",
                "package com.example; public class Helper {}",
            )

        val graph = UnifiedDependencyResolver.resolve(grammar, project)

        assertEquals(listOf(helper.canonicalFile), graph.javaFiles.map { it.file.canonicalFile })
        assertTrue(graph.unresolvedJavaImports.isEmpty())
    }

    fun `test dependency search never escapes the project boundary into a sibling`() {
        val grammar = grammarWithHeader("import com.example.Escaped;")
        val base = File(requireNotNull(project.basePath)).canonicalFile
        val outside = Files.createTempDirectory(base.parentFile.toPath(), "antlr4plus-resolver-sibling-")
        try {
            Files.writeString(
                outside.resolve("Escaped.java"),
                "package com.example; public class Escaped {}",
            )

            val graph = UnifiedDependencyResolver.resolve(grammar, project)

            assertTrue(
                "A source outside the project was included: ${graph.javaFiles}",
                graph.javaFiles.isEmpty(),
            )
            assertEquals(
                listOf("com.example.Escaped"),
                graph.unresolvedJavaImports.map { it.importStatement },
            )
        } finally {
            assertTrue(GeneratedWorkspaceFileOps.deleteTree(outside))
        }
    }

    fun `test linked source dependency is rejected instead of trusting its target`() {
        val grammar = grammarWithHeader("import com.example.Linked;")
        val base = File(requireNotNull(project.basePath)).canonicalFile
        val outside = Files.createTempDirectory(base.parentFile.toPath(), "antlr4plus-resolver-link-target-")
        val linkedSource = File(caseRoot, "src/Linked.java").toPath()
        try {
            val outsideSource = outside.resolve("Linked.java")
            Files.writeString(outsideSource, "package com.example; public class Linked {}")
            Files.createDirectories(linkedSource.parent)
            Files.createSymbolicLink(linkedSource, outsideSource)

            val graph = UnifiedDependencyResolver.resolve(grammar, project)

            assertTrue(
                "A linked source was included: ${graph.javaFiles}",
                graph.javaFiles.isEmpty(),
            )
            assertEquals(
                listOf("com.example.Linked"),
                graph.unresolvedJavaImports.map { it.importStatement },
            )
        } finally {
            Files.deleteIfExists(linkedSource)
            assertTrue(GeneratedWorkspaceFileOps.deleteTree(outside))
        }
    }

    fun `test qualified import selects the matching package instead of a nearer namesake`() {
        val grammar = grammarWithHeader("import com.example.Helper;")
        projectFile(
            "grammars/near/Helper.java",
            "package unrelated; public class Helper {}",
        )
        val matching =
            projectFile(
                "src/main/java/com/example/Helper.java",
                "package com.example; public class Helper {}",
            )

        val graph = UnifiedDependencyResolver.resolve(grammar, project)

        assertEquals(listOf(matching.canonicalFile), graph.javaFiles.map { it.file.canonicalFile })
        assertTrue(graph.unresolvedJavaImports.isEmpty())
    }

    fun `test named static import resolves its declaring source class`() {
        val grammar = grammarWithHeader("import static com.example.Util.answer;")
        val util =
            projectFile(
                "src/main/java/com/example/Util.java",
                "package com.example; public class Util { public static int answer = 42; }",
            )

        val graph = UnifiedDependencyResolver.resolve(grammar, project)

        assertEquals(listOf(util.canonicalFile), graph.javaFiles.map { it.file.canonicalFile })
        assertTrue(graph.unresolvedJavaImports.isEmpty())
    }

    fun `test semicolonless Kotlin import resolves its source dependency`() {
        val grammar =
            grammarWithHeader(
                header = "import com.example.Helper",
                options = "options { language = Kotlin; }",
            )
        val helper =
            projectFile(
                "src/main/kotlin/com/example/Helper.kt",
                "package com.example\nclass Helper",
            )

        val graph = UnifiedDependencyResolver.resolve(grammar, project)

        assertEquals(listOf(helper.canonicalFile), graph.javaFiles.map { it.file.canonicalFile })
        assertTrue(graph.unresolvedJavaImports.isEmpty())
    }

    fun `test local source in ANTLR namespace is tracked rather than silently trusted`() {
        val grammar = grammarWithHeader("import org.antlr.v4.runtime.Evil;")
        val localSource =
            projectFile(
                "src/main/java/org/antlr/v4/runtime/Evil.java",
                "package org.antlr.v4.runtime; public class Evil {}",
            )

        val graph = UnifiedDependencyResolver.resolve(grammar, project)

        assertEquals(
            listOf(localSource.canonicalFile),
            graph.javaFiles.map { it.file.canonicalFile },
        )
        assertTrue(graph.unresolvedJavaImports.isEmpty())
    }

    fun `test comments and strings do not invent grammar dependencies`() {
        val grammar =
            projectFile(
                "grammars/Comments.g4",
                """
                grammar Comments;
                // import MissingDelegate;
                // options { tokenVocab = MissingLexer; }
                @members { String text = "import StringDelegate; tokenVocab = StringLexer;"; }
                start : EOF;
                """
                    .trimIndent(),
            )

        val graph = UnifiedDependencyResolver.resolve(grammar, project)

        assertEquals(
            listOf(grammar.canonicalFile),
            graph.grammarFiles.map { it.file.canonicalFile },
        )
        assertTrue(
            "Comments produced unresolved imports: ${graph.unresolvedGrammarImports}",
            graph.unresolvedGrammarImports.isEmpty(),
        )
    }

    fun `test linked imported grammar is rejected instead of following it outside the project`() {
        val grammar =
            projectFile(
                "grammars/LinkedMain.g4",
                "grammar LinkedMain; import LinkedDelegate; start : delegated EOF;",
            )
        val base = File(requireNotNull(project.basePath)).canonicalFile
        val outside = Files.createTempDirectory(base.parentFile.toPath(), "antlr4plus-grammar-link-target-")
        val linkedGrammar = File(caseRoot, "grammars/LinkedDelegate.g4").toPath()
        try {
            val outsideGrammar = outside.resolve("LinkedDelegate.g4")
            Files.writeString(outsideGrammar, "parser grammar LinkedDelegate; delegated : ID;")
            Files.createSymbolicLink(linkedGrammar, outsideGrammar)

            val graph = UnifiedDependencyResolver.resolve(grammar, project)

            assertEquals(
                listOf(grammar.canonicalFile),
                graph.grammarFiles.map { it.file.canonicalFile },
            )
            assertEquals(listOf("LinkedDelegate"), graph.unresolvedGrammarImports)
        } finally {
            Files.deleteIfExists(linkedGrammar)
            assertTrue(GeneratedWorkspaceFileOps.deleteTree(outside))
        }
    }

    fun `test directory named like a grammar cannot satisfy an import`() {
        val grammar =
            projectFile(
                "grammars/DirectoryMain.g4",
                "grammar DirectoryMain; import DirectoryDelegate; start : EOF;",
            )
        Files.createDirectories(File(caseRoot, "grammars/DirectoryDelegate.g4").toPath())

        val graph = UnifiedDependencyResolver.resolve(grammar, project)

        assertEquals(
            listOf(grammar.canonicalFile),
            graph.grammarFiles.map { it.file.canonicalFile },
        )
        assertEquals(listOf("DirectoryDelegate"), graph.unresolvedGrammarImports)
    }

    fun `test package wildcard import tracks every local source in that package`() {
        val grammar = grammarWithHeader("import com.example.*;")
        val javaSource =
            projectFile(
                "src/main/java/com/example/First.java",
                "package com.example; public class First {}",
            )
        val kotlinSource =
            projectFile(
                "src/main/kotlin/com/example/Second.kt",
                "package com.example\nclass Second",
            )
        projectFile(
            "src/main/java/com/other/Unrelated.java",
            "package com.other; public class Unrelated {}",
        )

        val graph = UnifiedDependencyResolver.resolve(grammar, project)

        assertEquals(
            setOf(javaSource.canonicalFile, kotlinSource.canonicalFile),
            graph.javaFiles.map { it.file.canonicalFile }.toSet(),
        )
        assertTrue(graph.unresolvedJavaImports.isEmpty())
    }

    fun `test transitive semicolonless Kotlin import is included in the dependency graph`() {
        val grammar = grammarWithHeader("import com.example.First;")
        val first =
            projectFile(
                "src/main/kotlin/com/example/First.kt",
                "package com.example\nimport com.example.Second\nclass First(val second: Second)",
            )
        val second =
            projectFile(
                "src/main/kotlin/com/example/Second.kt",
                "package com.example\nclass Second",
            )

        val graph = UnifiedDependencyResolver.resolve(grammar, project)

        assertEquals(
            setOf(first.canonicalFile, second.canonicalFile),
            graph.javaFiles.map { it.file.canonicalFile }.toSet(),
        )
        assertTrue(graph.unresolvedJavaImports.isEmpty())
    }

    fun `test resolving one qualified namesake does not hide another unresolved import`() {
        val grammar =
            grammarWithHeader(
                """
                import com.example.Found;
                import com.missing.Found;
                """
                    .trimIndent(),
            )
        val found =
            projectFile(
                "src/main/java/com/example/Found.java",
                "package com.example; public class Found {}",
            )

        val graph = UnifiedDependencyResolver.resolve(grammar, project)

        assertEquals(listOf(found.canonicalFile), graph.javaFiles.map { it.file.canonicalFile })
        assertEquals(
            listOf("com.missing.Found"),
            graph.unresolvedJavaImports.map { it.importStatement },
        )
    }

    fun `test Kotlin import alias resolves the original qualified class`() {
        val grammar =
            grammarWithHeader(
                header = "import com.example.Helper as Alias",
                options = "options { language = Kotlin; }",
            )
        val helper =
            projectFile(
                "src/main/kotlin/com/example/Helper.kt",
                "package com.example\nclass Helper",
            )

        val graph = UnifiedDependencyResolver.resolve(grammar, project)

        assertEquals(listOf(helper.canonicalFile), graph.javaFiles.map { it.file.canonicalFile })
        assertTrue(graph.unresolvedJavaImports.isEmpty())
    }

    fun `test grammar imports and token vocab in comments and strings are ignored`() {
        val grammar =
            projectFile(
                "grammars/Decoys.g4",
                """
                grammar Decoys;
                // import MissingDelegate;
                // options { tokenVocab = MissingLexer; }
                @members { String text = "import StringDelegate; tokenVocab = StringLexer;"; }
                start : EOF;
                """
                    .trimIndent(),
            )

        val graph = UnifiedDependencyResolver.resolve(grammar, project)

        assertEquals(
            listOf(grammar.canonicalFile),
            graph.grammarFiles.map { it.file.canonicalFile },
        )
        assertTrue(graph.unresolvedGrammarImports.isEmpty())
    }

    fun `test grammar dependency resolution rejects a symbolic link import`() {
        val target =
            projectFile(
                "targets/LinkedDelegate.g4",
                "parser grammar LinkedDelegate; delegated : ID;",
            )
        val grammar =
            projectFile(
                "grammars/LinkedMain.g4",
                "grammar LinkedMain; import LinkedDelegate; start : EOF;",
            )
        Files.createSymbolicLink(
            File(grammar.parentFile, "LinkedDelegate.g4").toPath(),
            target.toPath(),
        )

        val graph = UnifiedDependencyResolver.resolve(grammar, project)

        assertEquals(
            listOf(grammar.canonicalFile),
            graph.grammarFiles.map { it.file.canonicalFile },
        )
        assertEquals(listOf("LinkedDelegate"), graph.unresolvedGrammarImports)
    }

    fun `test UTF-16 imported grammar code appears in the dependency graph`() {
        val delegate =
            projectFile(
                "grammars/EncodedDelegate.g4",
                "parser grammar EncodedDelegate; delegated : ID { recordToken(); } ;",
                Charsets.UTF_16LE,
            )
        val main =
            projectFile(
                "grammars/EncodedMain.g4",
                """
                grammar EncodedMain;
                import EncodedDelegate;
                start : delegated EOF { finished(); } ;
                ID : [a-z]+ ;
                """
                    .trimIndent(),
                Charsets.UTF_16LE,
            )

        val graph = UnifiedDependencyResolver.resolve(main, project, grammarCharset = Charsets.UTF_16LE)

        assertTrue(graph.unresolvedGrammarImports.isEmpty())
        assertEquals(
            setOf(main.canonicalFile, delegate.canonicalFile),
            graph.grammarFiles.map { it.file.canonicalFile }.toSet(),
        )
        assertTrue(
            graph.grammarFiles
                .single { it.file.canonicalFile == main.canonicalFile }
                .embeddedCode
                .any {
                    "finished()" in it.content
                },
        )
        assertTrue(
            graph.grammarFiles
                .single { it.file.canonicalFile == delegate.canonicalFile }
                .embeddedCode
                .any {
                    "recordToken()" in it.content
                },
        )
    }

    private fun grammarWithHeader(header: String, options: String = ""): File =
        projectFile(
            "grammars/Demo.g4",
            """
        grammar Demo;
        $options
        @header {
        $header
        }
        start : EOF;
        """
                .trimIndent(),
        )

    private fun projectFile(
        relativePath: String,
        content: String,
        charset: Charset = Charsets.UTF_8,
    ): File {
        val file = File(caseRoot, relativePath)
        assertTrue(file.parentFile.mkdirs() || file.parentFile.isDirectory)
        file.writeText(content, charset)
        return file
    }
}
