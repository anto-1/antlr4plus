package com.antlr4plus.parsing

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Test

/** Tests filesystem dependency discovery when the IDE index cannot find source files. */
class ProjectTreeWalkFallbackTest {

    private lateinit var root: File

    private fun createRoot(prefix: String = "antlr4plus-walk-test"): File =
        createTempDirectory(prefix).toFile().canonicalFile

    @After
    fun cleanup() {
        if (::root.isInitialized && root.exists()) {
            root.deleteRecursively()
        }
    }

    private fun mkfile(relative: String, content: String = "// test\n"): File {
        val f = File(root, relative)
        f.parentFile.mkdirs()
        f.writeText(content)
        return f
    }

    private fun buildCustomerLayout() {
        root = createRoot()
        mkfile("algol60/customer_repro/grammars/CustomerLexer.g4")
        mkfile("algol60/customer_repro/grammars/CustomerParser.g4")
        mkfile("algol60/customer_repro/input/sample.txt.g4p")
        mkfile("algol60/customer_repro/examples/lexer-side/CustomerLexerBase.kt")
        mkfile("algol60/customer_repro/examples/parser-side/CustomerContextBase.kt")
    }

    @Test
    fun `walk finds files inside examples directory`() {
        buildCustomerLayout()

        val results =
            CompiledSourceSearch.walkProjectForExactNames(
                root = root,
                targetFileNames = setOf("CustomerLexerBase.kt", "CustomerContextBase.kt"),
            )

        val byName = results.associateBy { it.name }
        assertTrue(
            "CustomerLexerBase.kt" in byName,
            "Phase 4 walk must find CustomerLexerBase.kt despite it living under examples/",
        )
        assertTrue(
            "CustomerContextBase.kt" in byName,
            "Phase 4 walk must find CustomerContextBase.kt despite it living under examples/",
        )

        assertEquals(
            "algol60/customer_repro/examples/lexer-side/CustomerLexerBase.kt",
            byName["CustomerLexerBase.kt"]!!.relativeTo(root).path.replace(File.separatorChar, '/'),
        )
        assertEquals(
            "algol60/customer_repro/examples/parser-side/CustomerContextBase.kt",
            byName["CustomerContextBase.kt"]!!.relativeTo(root).path.replace(File.separatorChar, '/'),
        )
    }

    @Test
    fun `walk finds files inside test directory`() {
        root = createRoot()
        mkfile("src/test/kotlin/com/example/MyParserTestBase.kt")

        val results =
            CompiledSourceSearch.walkProjectForExactNames(
                root = root,
                targetFileNames = setOf("MyParserTestBase.kt"),
            )

        assertEquals(1, results.size)
        assertEquals("MyParserTestBase.kt", results[0].name)
    }

    @Test
    fun `walk finds files inside gen directory`() {
        root = createRoot()
        mkfile("src/main/gen/com/example/HandwrittenAlongsideGenerated.kt")

        val results =
            CompiledSourceSearch.walkProjectForExactNames(
                root = root,
                targetFileNames = setOf("HandwrittenAlongsideGenerated.kt"),
            )

        assertEquals(1, results.size)
    }

    @Test
    fun `walk skips build and dot-git directories for performance`() {
        root = createRoot()
        mkfile("build/classes/MyClass.kt")
        mkfile(".git/objects/MyClass.kt")
        mkfile("real/MyClass.kt")

        val results =
            CompiledSourceSearch.walkProjectForExactNames(
                root = root,
                targetFileNames = setOf("MyClass.kt"),
            )

        assertEquals(1, results.size, "Found: ${results.map { it.absolutePath }}")
        assertEquals("real", results[0].parentFile.name)
    }

    @Test
    fun `walk finds multiple files with the same name in different locations`() {
        // Return all candidates so dependency resolution can handle ambiguous names.
        root = createRoot()
        mkfile("module-a/src/MyBase.kt")
        mkfile("module-b/src/MyBase.kt")
        mkfile("vendor/MyBase.kt")

        val results =
            CompiledSourceSearch.walkProjectForExactNames(
                root = root,
                targetFileNames = setOf("MyBase.kt"),
            )

        assertEquals(3, results.size, "Found: ${results.map { it.relativeTo(root) }}")
    }

    @Test
    fun `walk returns empty for empty target set`() {
        root = createRoot()
        mkfile("src/Foo.kt")

        val results =
            CompiledSourceSearch.walkProjectForExactNames(
                root = root,
                targetFileNames = emptySet(),
            )
        assertTrue(results.isEmpty())
    }

    @Test
    fun `walk returns empty for non-existent root`() {
        root = createRoot()
        val phantom = File(root, "does-not-exist")

        val results =
            CompiledSourceSearch.walkProjectForExactNames(
                root = phantom,
                targetFileNames = setOf("Anything.kt"),
            )
        assertTrue(results.isEmpty())
    }

    @Test
    fun `walk finds Java files alongside Kotlin files`() {
        root = createRoot()
        mkfile("module-kt/src/MixedBase.kt")
        mkfile("module-java/src/MixedBase.java")

        val results =
            CompiledSourceSearch.walkProjectForExactNames(
                root = root,
                targetFileNames = setOf("MixedBase.kt", "MixedBase.java"),
            )

        assertEquals(2, results.size)
        val extensions = results.map { it.extension }.toSet()
        assertEquals(setOf("kt", "java"), extensions)
    }

    @Test
    fun `flood fill scope rejects sibling directory with project-name prefix`() {
        root = createRoot("antlr4plus-scope-parent-")
        val project = File(root, "project").apply { mkdirs() }
        val inside = File(project, "grammars").apply { mkdirs() }
        val sibling = File(root, "project-copy/grammars").apply { mkdirs() }

        assertTrue(CompiledSourceSearch.isWithinProjectScope(inside, project))
        assertTrue(!CompiledSourceSearch.isWithinProjectScope(sibling, project))
    }
}
