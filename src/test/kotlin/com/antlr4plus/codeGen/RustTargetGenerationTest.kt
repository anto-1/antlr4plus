package com.antlr4plus.codeGen

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.TimeUnit
import junit.framework.TestCase
import org.antlr.v4.Tool
import org.antlr.v4.tool.ANTLRMessage
import org.antlr.v4.tool.ANTLRToolListener

/** Offline contract tests for the product-owned Rust target and template. */
class RustTargetGenerationTest : TestCase() {

    private lateinit var fixtureRoot: Path

    override fun setUp() {
        super.setUp()
        fixtureRoot = Files.createTempDirectory("antlr4plus-rust-target-")
    }

    override fun tearDown() {
        try {
            fixtureRoot.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    fun `test Rust appears exactly once in the settings target registry`() {
        val languages = GenAntlrCode.SUPPORTED_TARGET_LANGUAGES

        assertEquals(1, languages.count { it == "Rust" })
        assertEquals(languages.sortedWith(String.CASE_INSENSITIVE_ORDER), languages)
    }

    fun `test product classpath contains the local Rust target and template`() {
        assertEquals(
            "org.antlr.v4.codegen.target.RustTarget",
            Class.forName("org.antlr.v4.codegen.target.RustTarget").name,
        )

        val template =
            requireNotNull(
                    javaClass.classLoader.getResourceAsStream(
                        "org/antlr/v4/tool/templates/codegen/Rust/Rust.stg",
                    ),
                )
                .bufferedReader()
                .use { it.readText() }

        assertTrue(template.contains("antlr4rust::const_check::version(\"0\", \"6\")"))
        assertFalse(template.contains("std::process::Command"))
        assertFalse(template.contains("include_bytes!"))
    }

    fun `test combined grammar emits the complete expected Rust source layout`() {
        val result =
            generate(
                grammarName = "Input.g4",
                grammar = SIMPLE_GRAMMAR,
                listener = true,
                visitor = true,
                outputName = "complete",
            )

        assertTrue(
            result.names.containsAll(
                setOf(
                    "inputlexer.rs",
                    "inputparser.rs",
                    "inputlistener.rs",
                    "inputbaselistener.rs",
                    "inputvisitor.rs",
                    "inputbasevisitor.rs",
                ),
            ),
        )
        assertTrue(
            result.text("inputparser.rs").contains("antlr4rust::const_check::version(\"0\", \"6\")"),
        )
    }

    fun `test listener and visitor switches are independent`() {
        val recognizersOnly =
            generate(
                grammarName = "Input.g4",
                grammar = SIMPLE_GRAMMAR,
                listener = false,
                visitor = false,
                outputName = "recognizers-only",
            )
        assertEquals(setOf("inputlexer.rs", "inputparser.rs"), recognizersOnly.rustNames)

        val visitorOnly =
            generate(
                grammarName = "Input.g4",
                grammar = SIMPLE_GRAMMAR,
                listener = false,
                visitor = true,
                outputName = "visitor-only",
            )
        assertEquals(
            setOf("inputlexer.rs", "inputparser.rs", "inputvisitor.rs", "inputbasevisitor.rs"),
            visitorOnly.rustNames,
        )

        val listenerOnly =
            generate(
                grammarName = "Input.g4",
                grammar = SIMPLE_GRAMMAR,
                listener = true,
                visitor = false,
                outputName = "listener-only",
            )
        assertEquals(
            setOf("inputlexer.rs", "inputparser.rs", "inputlistener.rs", "inputbaselistener.rs"),
            listenerOnly.rustNames,
        )
    }

    fun `test Rust filenames are locale independent`() {
        synchronized(Locale::class.java) {
            val previous = Locale.getDefault()
            try {
                Locale.setDefault(Locale.forLanguageTag("tr-TR"))
                val result =
                    generate(
                        grammarName = "Input.g4",
                        grammar = SIMPLE_GRAMMAR,
                        listener = true,
                        visitor = true,
                        outputName = "turkish-locale",
                    )

                assertTrue(
                    "Expected ASCII lowercase filenames: ${result.rustNames}",
                    result.rustNames.all {
                        it.none { character -> character == '\u0130' || character == '\u0131' }
                    },
                )
                assertTrue("inputparser.rs" in result.rustNames)
                assertTrue("inputvisitor.rs" in result.rustNames)
            } finally {
                Locale.setDefault(previous)
            }
        }
    }

    fun `test reserved rule names are escaped and embedded Rust is emitted but not executed`() {
        val result =
            generate(
                grammarName = "Features.g4",
                grammar =
                    """
                    grammar Features;
                    @parser::members {
                        const ANTLR4PLUS_ACTION_MARKER: &str = "source-only";
                    }
                    start: type EOF;
                    type: TYPE { let action_marker = "ANTLR4PLUS_RULE_ACTION"; };
                    TYPE: 'type';
                    WS: [ \t\r\n]+ -> skip;
                    """
                        .trimIndent(),
                listener = false,
                visitor = false,
                outputName = "features",
            )

        val parser = result.text("featuresparser.rs")
        assertTrue(parser.contains("pub fn type_"))
        assertTrue(parser.contains("ANTLR4PLUS_ACTION_MARKER"))
        assertTrue(parser.contains("ANTLR4PLUS_RULE_ACTION"))
    }

    fun `test regeneration is deterministic and overwrites the same source files`() {
        val first =
            generate(
                grammarName = "Input.g4",
                grammar = SIMPLE_GRAMMAR,
                listener = true,
                visitor = true,
                outputName = "replaceable",
            )
        val expectedSources = first.rustFiles
        Files.writeString(first.output.resolve("inputparser.rs"), "corrupted")

        val regenerated =
            generate(
                grammarName = "Input.g4",
                grammar = SIMPLE_GRAMMAR,
                listener = true,
                visitor = true,
                outputName = "replaceable",
            )
        val independent =
            generate(
                grammarName = "Input.g4",
                grammar = SIMPLE_GRAMMAR,
                listener = true,
                visitor = true,
                outputName = "independent",
            )

        assertEquals(expectedSources, regenerated.rustFiles)
        assertEquals(expectedSources, independent.rustFiles)
    }

    fun `test generation emits text sources and ANTLR metadata only`() {
        val result =
            generate(
                grammarName = "Input.g4",
                grammar = SIMPLE_GRAMMAR,
                listener = true,
                visitor = true,
                outputName = "source-only",
            )

        val allowedExtensions = setOf("interp", "rs", "tokens")
        assertTrue(
            "Unexpected generated artifact: ${result.names}",
            result.names.all {
                it.substringAfterLast('.', missingDelimiterValue = "") in allowedExtensions
            },
        )
        assertTrue(
            "Generated output contained binary NUL bytes",
            result.files.values.all { bytes ->
                bytes.none { it == 0.toByte() }
            },
        )
    }

    fun `test generated Rust parses with explicitly configured rustfmt`() {
        val rustfmtProperty = System.getProperty("antlr4plus.rustfmt")?.takeIf { it.isNotBlank() } ?: return
        // Preserve rustup proxy symlinks: resolving rustfmt to the rustup binary changes dispatch.
        val rustfmt = File(rustfmtProperty).absoluteFile.normalize()
        assertTrue("rustfmt must be an absolute regular executable: $rustfmt", rustfmt.isAbsolute)
        assertTrue("rustfmt must be an absolute regular executable: $rustfmt", rustfmt.isFile)
        assertTrue("rustfmt must be an absolute regular executable: $rustfmt", rustfmt.canExecute())

        val result =
            generate(
                grammarName = "Input.g4",
                grammar = SIMPLE_GRAMMAR,
                listener = true,
                visitor = true,
                outputName = "rustfmt",
            )
        result.rustNames.sorted().forEach { fileName ->
            val diagnostics = fixtureRoot.resolve("rustfmt-$fileName.log").toFile()
            val process =
                ProcessBuilder(
                        rustfmt.absolutePath,
                        "--edition",
                        "2021",
                        "--emit",
                        "stdout",
                        result.output.resolve(fileName).toString(),
                    )
                    .redirectErrorStream(true)
                    .redirectOutput(diagnostics)
                    .start()
            val finished = process.waitFor(30, TimeUnit.SECONDS)
            if (!finished) process.destroyForcibly()
            assertTrue("rustfmt timed out for $fileName", finished)
            assertEquals(
                "rustfmt rejected $fileName:\n${diagnostics.takeIf(File::isFile)?.readText().orEmpty()}",
                0,
                process.exitValue(),
            )
        }
    }

    private fun generate(
        grammarName: String,
        grammar: String,
        listener: Boolean,
        visitor: Boolean,
        outputName: String,
    ): GeneratedOutput {
        val grammarDirectory = fixtureRoot.resolve("grammars")
        Files.createDirectories(grammarDirectory)
        val grammarFile = grammarDirectory.resolve(grammarName)
        Files.writeString(grammarFile, grammar)

        val output = fixtureRoot.resolve(outputName)
        val arguments = buildList {
            add("-Dlanguage=Rust")
            add("-o")
            add(output.toString())
            add("-lib")
            add(grammarDirectory.toString())
            add("-encoding")
            add("UTF-8")
            if (!listener) add("-no-listener")
            add(if (visitor) "-visitor" else "-no-visitor")
            add(grammarFile.toString())
        }

        val errors = mutableListOf<String>()
        val tool = Tool(arguments.toTypedArray())
        tool.removeListeners()
        tool.addListener(
            object : ANTLRToolListener {
                override fun info(msg: String?) = Unit

                override fun error(msg: ANTLRMessage) {
                    errors += msg.toString()
                }

                override fun warning(msg: ANTLRMessage?) = Unit
            },
        )
        tool.processGrammarsOnCommandLine()

        assertEquals("ANTLR generation errors:\n${errors.joinToString("\n")}", 0, tool.numErrors)
        assertTrue("ANTLR listener errors:\n${errors.joinToString("\n")}", errors.isEmpty())
        assertTrue("ANTLR did not create $output", Files.isDirectory(output))

        val files = linkedMapOf<String, ByteArray>()
        Files.walk(output).use { paths ->
            paths
                .filter { Files.isRegularFile(it) }
                .sorted()
                .forEach { file ->
                    val relative = output.relativize(file).toString().replace(File.separatorChar, '/')
                    files[relative] = Files.readAllBytes(file)
                }
        }
        return GeneratedOutput(output, files)
    }

    private data class GeneratedOutput(
        val output: Path,
        val files: Map<String, ByteArray>,
    ) {
        val names: Set<String>
            get() = files.keys

        val rustNames: Set<String>
            get() = names.filterTo(mutableSetOf()) { it.endsWith(".rs") }

        val rustFiles: Map<String, List<Byte>>
            get() = files.filterKeys { it.endsWith(".rs") }.mapValues { (_, bytes) -> bytes.toList() }

        fun text(name: String): String =
            requireNotNull(files[name]) {
                    "Missing generated file '$name'; available: $names"
                }
                .toString(Charsets.UTF_8)
    }

    private companion object {
        val SIMPLE_GRAMMAR =
            """
            grammar Input;
            start: ID EOF;
            ID: [a-zA-Z]+;
            WS: [ \t\r\n]+ -> skip;
            """
                .trimIndent()
    }
}
