package com.antlr4plus.integration

import com.antlr4plus.filebinding.BindingOperations
import com.antlr4plus.onboarding.GrammarsV4Bindings
import com.antlr4plus.parsing.ParsingUtils
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.isRegularFile
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.antlr.v4.Tool
import org.antlr.v4.tool.ANTLRMessage
import org.antlr.v4.tool.ANTLRToolListener
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Exercises a grammars-v4 checkout; generated output goes to JUnit temporary directories. Run: `./gradlew
 * grammarsV4IntegrationTest -PgrammarsV4Dir=/path/to/grammars-v4`
 */
class GrammarsV4FixtureIntegrationTest {

    private val knownUpstreamGenerationFailures =
        mapOf(
            // The pinned TrapC fixture imports C.g4, which is absent from its upstream c directory.
            "trapc/TrapC.g4" to "can't find or load grammar C",
        )

    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun `every bundled binding resolves to a grammar and an input`() {
        val fixtureRoot = fixtureRoot()
        val bindings = BindingOperations.parseBindings(GrammarsV4Bindings.BINDINGS)

        assertEquals(20_089, bindings.size, "The bundled manifest entry count changed")
        assertEquals(
            bindings.size,
            bindings.map { it.g4pPath }.toSet().size,
            "Duplicate profile paths",
        )
        assertEquals(
            348,
            bindings.map { it.grammarPath }.toSet().size,
            "The grammar coverage changed",
        )

        val unsafePaths = bindings.flatMap { binding ->
            listOf(binding.g4pPath, binding.grammarPath).filterNot(::isSafeRelativePath)
        }
        assertTrue(unsafePaths.isEmpty(), "Manifest contains unsafe paths: ${unsafePaths.take(20)}")

        val plan = BindingOperations.buildExecutionPlan(fixtureRoot, bindings)
        val skipped = plan.filterIsInstance<BindingOperations.PlannedAction.Skip>()
        assertTrue(
            skipped.isEmpty(),
            buildString {
                append("Bindings that cannot be applied to grammars-v4:\n")
                skipped.take(50).forEach {
                    append("  ${it.g4pPath} -> ${it.grammarPath}: ${it.reason}\n")
                }
                if (skipped.size > 50) append("  ... and ${skipped.size - 50} more")
            },
        )
        assertEquals(bindings.size, plan.size)
    }

    @Test
    fun `all manifest grammars generate Java except pinned upstream quarantines`() {
        val fixtureRoot = fixtureRoot()
        val grammarPaths =
            BindingOperations.parseBindings(GrammarsV4Bindings.BINDINGS).map { it.grammarPath }.distinct().sorted()
        val outputRoot = temporaryFolder.newFolder("antlr-output").toPath()
        val failures = linkedMapOf<String, String>()

        grammarPaths.forEachIndexed { index, relativeGrammarPath ->
            val grammarFile = fixtureRoot.resolve(relativeGrammarPath).normalize()
            val outputDirectory = outputRoot.resolve(index.toString().padStart(3, '0'))
            Files.createDirectories(outputDirectory)

            val inputs = linkedSetOf(grammarFile.toString())
            ParsingUtils.getLexerFileForParser(grammarFile.toString())?.let(inputs::add)
            val arguments =
                mutableListOf(
                    "-Dlanguage=Java",
                    "-Xexact-output-dir",
                    "-o",
                    outputDirectory.toString(),
                    "-lib",
                    grammarFile.parent.toString(),
                )
            arguments.addAll(inputs)

            val tool = Tool(arguments.toTypedArray())
            val listener = ErrorCollectingToolListener(tool)
            tool.removeListeners()
            tool.addListener(listener)

            try {
                tool.processGrammarsOnCommandLine()
            } catch (failure: Throwable) {
                listener.errors += "${failure::class.java.simpleName}: ${failure.message}"
            }

            val generatedJava =
                Files.list(outputDirectory).use { files ->
                    files.anyMatch {
                        it.isRegularFile() && it.fileName.toString().endsWith(".java")
                    }
                }
            if (listener.errors.isNotEmpty() || !generatedJava) {
                failures[relativeGrammarPath] = buildString {
                    if (!generatedJava) append(": generated no Java files")
                    listener.errors.take(5).forEach { append("\n    $it") }
                }
            }
        }

        val unexpectedFailures = failures.filterKeys { it !in knownUpstreamGenerationFailures }
        val changedKnownFailures = knownUpstreamGenerationFailures.filter { (path, expectedMessage) ->
            failures[path]?.contains(expectedMessage) != true
        }
        assertTrue(
            unexpectedFailures.isEmpty() && changedKnownFailures.isEmpty(),
            buildString {
                append("Unexpected generation results for ${grammarPaths.size} bound grammars:\n")
                unexpectedFailures.entries.take(50).forEach { (path, details) ->
                    append("  $path$details\n")
                }
                changedKnownFailures.forEach { (path, expected) ->
                    append("  $path did not fail with the quarantined upstream error: $expected\n")
                }
            },
        )
    }

    private fun fixtureRoot(): Path {
        val configured =
            System.getProperty("grammarsV4Dir")
                ?: error("grammarsV4Dir was not supplied by the grammarsV4IntegrationTest task")
        val root = Paths.get(configured).toAbsolutePath().normalize()
        assertTrue(Files.isDirectory(root), "grammars-v4 fixture is not a directory: $root")
        return root
    }

    private fun isSafeRelativePath(value: String): Boolean {
        if (value.isBlank() || value.contains('\\')) return false
        val path =
            try {
                Paths.get(value)
            } catch (_: RuntimeException) {
                return false
            }
        return !path.isAbsolute && path.normalize().none { it.toString() == ".." }
    }

    private class ErrorCollectingToolListener(private val tool: Tool) : ANTLRToolListener {
        val errors = mutableListOf<String>()

        override fun info(msg: String) = Unit

        override fun error(msg: ANTLRMessage) {
            errors += tool.errMgr.getMessageTemplate(msg).render()
        }

        override fun warning(msg: ANTLRMessage) = Unit
    }
}
