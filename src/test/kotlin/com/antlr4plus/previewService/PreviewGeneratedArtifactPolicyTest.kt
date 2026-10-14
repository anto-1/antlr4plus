package com.antlr4plus.previewService

import java.nio.file.Files
import java.nio.file.LinkOption
import junit.framework.TestCase
import org.antlr.v4.Tool

class PreviewGeneratedArtifactPolicyTest : TestCase() {
    private lateinit var session: PreviewGeneratedArtifactPolicy.SessionCache

    override fun setUp() {
        super.setUp()
        session = PreviewGeneratedArtifactPolicy.SessionCache()
    }

    fun `test regeneration preserves sources owned by users and other grammars`() {
        val root = Files.createTempDirectory("antlr4plus-preview-clean-")
        try {
            val grammar = root.resolve("Demo.g4")
            Files.writeString(grammar, "grammar Demo; start: EOF;")
            val output = Files.createDirectories(root.resolve("output"))
            val plan =
                PreviewGeneratedArtifactPolicy.buildPlan(
                    parserGrammarFile = grammar.toFile(),
                    lexerGrammarFile = null,
                    charset = Charsets.UTF_8,
                    sourceExtension = "java",
                    generateListener = true,
                    generateVisitor = true,
                )

            Files.writeString(output.resolve("DemoParser.java"), "stale parser")
            Files.writeString(output.resolve("DemoLexer.java"), "stale lexer")
            Files.writeString(output.resolve("KeepMe.java"), "handwritten")
            Files.writeString(output.resolve("OtherParser.java"), "other grammar")
            val mapDir = Files.createDirectories(output.resolve(".antlr4plus"))
            Files.writeString(mapDir.resolve("DemoParser.java.map"), "stale map")
            Files.writeString(mapDir.resolve("OtherParser.java.map"), "other map")
            Files.writeString(mapDir.resolve("dependencies.sha256"), "current snapshot")
            Files.writeString(
                mapDir.resolve("dependencies-0123456789abcdefabcd.sha256"),
                "legacy snapshot",
            )
            Files.writeString(
                Files.createDirectories(output.resolve("compiled")).resolve("DemoParser.class"),
                "stale",
            )

            PreviewGeneratedArtifactPolicy.cleanForRegeneration(output.toFile(), plan)

            assertFalse(Files.exists(output.resolve("DemoParser.java")))
            assertFalse(Files.exists(output.resolve("DemoLexer.java")))
            assertFalse(Files.exists(mapDir.resolve("DemoParser.java.map")))
            assertFalse(Files.exists(mapDir.resolve("dependencies.sha256")))
            assertFalse(Files.exists(mapDir.resolve("dependencies-0123456789abcdefabcd.sha256")))
            assertFalse(Files.exists(output.resolve("compiled")))
            assertTrue(Files.exists(output.resolve("KeepMe.java")))
            assertTrue(Files.exists(output.resolve("OtherParser.java")))
            assertFalse(Files.exists(mapDir))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    fun `test cache reuse requires every source for the current grammar`() {
        val root = Files.createTempDirectory("antlr4plus-preview-cache-")
        try {
            val grammar = root.resolve("Demo.g4")
            Files.writeString(grammar, "grammar Demo; start: EOF;")
            val output = Files.createDirectories(root.resolve("output"))
            val plan =
                PreviewGeneratedArtifactPolicy.buildPlan(
                    parserGrammarFile = grammar.toFile(),
                    lexerGrammarFile = null,
                    charset = Charsets.UTF_8,
                    sourceExtension = "java",
                    generateListener = false,
                    generateVisitor = false,
                )

            Files.writeString(output.resolve("OtherParser.java"), "other grammar")
            assertFalse(
                session.hasReusableSources(
                    output.toFile(),
                    plan,
                    listOf(grammar.toFile()),
                ),
            )

            plan.requiredSourceNames.forEach { name ->
                val source = output.resolve(name)
                Files.writeString(source, name)
                source.toFile().setLastModified(grammar.toFile().lastModified() + 1_000)
            }
            assertTrue(
                session.recordGeneration(
                    output.toFile(),
                    plan,
                    listOf(grammar.toFile()),
                ),
            )
            assertTrue(
                session.hasReusableSources(
                    output.toFile(),
                    plan,
                    listOf(grammar.toFile()),
                ),
            )

            Files.delete(output.resolve("DemoParser.java"))
            assertFalse(
                session.hasReusableSources(
                    output.toFile(),
                    plan,
                    listOf(grammar.toFile()),
                ),
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    fun `test cache reuse detects imported grammar edits even when timestamp is preserved`() {
        val root = Files.createTempDirectory("antlr4plus-preview-import-cache-")
        try {
            val grammar = root.resolve("Demo.g4")
            val imported = root.resolve("Delegate.g4")
            Files.writeString(
                grammar,
                "parser grammar Demo; import Delegate; start: delegated EOF;",
            )
            Files.writeString(imported, "parser grammar Delegate; delegated: 'before';")
            val output = Files.createDirectories(root.resolve("output"))
            val plan =
                PreviewGeneratedArtifactPolicy.buildPlan(
                    parserGrammarFile = grammar.toFile(),
                    lexerGrammarFile = null,
                    charset = Charsets.UTF_8,
                    sourceExtension = "java",
                    generateListener = false,
                    generateVisitor = false,
                )
            plan.requiredSourceNames.forEach { name ->
                Files.writeString(output.resolve(name), name)
            }
            val inputs = listOf(grammar.toFile(), imported.toFile())
            assertTrue(session.recordGeneration(output.toFile(), plan, inputs))
            assertTrue(session.hasReusableSources(output.toFile(), plan, inputs))

            val originalTimestamp = imported.toFile().lastModified()
            Files.writeString(imported, "parser grammar Delegate; delegated: 'after';")
            assertTrue(imported.toFile().setLastModified(originalTimestamp))

            assertFalse(
                "Changing an imported grammar must invalidate generated and compiled preview caches",
                session.hasReusableSources(output.toFile(), plan, inputs),
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    fun `test shared runtime directory never reuses another binding with the same grammar name`() {
        val root = Files.createTempDirectory("antlr4plus-preview-owner-")
        try {
            val firstGrammar = root.resolve("first/Demo.g4")
            val secondGrammar = root.resolve("second/Demo.g4")
            Files.createDirectories(firstGrammar.parent)
            Files.createDirectories(secondGrammar.parent)
            Files.writeString(firstGrammar, "grammar Demo; start: EOF;")
            Files.writeString(secondGrammar, "grammar Demo; start: EOF;")
            val output = Files.createDirectories(root.resolve("output"))
            val firstPlan =
                PreviewGeneratedArtifactPolicy.buildPlan(
                    parserGrammarFile = firstGrammar.toFile(),
                    lexerGrammarFile = null,
                    charset = Charsets.UTF_8,
                    sourceExtension = "java",
                    generateListener = false,
                    generateVisitor = false,
                )
            val secondPlan =
                PreviewGeneratedArtifactPolicy.buildPlan(
                    parserGrammarFile = secondGrammar.toFile(),
                    lexerGrammarFile = null,
                    charset = Charsets.UTF_8,
                    sourceExtension = "java",
                    generateListener = false,
                    generateVisitor = false,
                )
            val changedPackagePlan =
                PreviewGeneratedArtifactPolicy.buildPlan(
                    parserGrammarFile = firstGrammar.toFile(),
                    lexerGrammarFile = null,
                    charset = Charsets.UTF_8,
                    sourceExtension = "java",
                    generateListener = false,
                    generateVisitor = false,
                    packageName = "example.preview",
                )
            assertEquals(firstPlan.requiredSourceNames, secondPlan.requiredSourceNames)
            firstPlan.requiredSourceNames.forEach { name ->
                Files.writeString(output.resolve(name), name)
            }

            assertTrue(
                session.recordGeneration(
                    output.toFile(),
                    firstPlan,
                    listOf(firstGrammar.toFile()),
                ),
            )
            assertTrue(
                session.hasReusableSources(
                    output.toFile(),
                    firstPlan,
                    listOf(firstGrammar.toFile()),
                ),
            )
            assertFalse(
                "A flat preview directory must be regenerated when its active binding changes",
                session.hasReusableSources(
                    output.toFile(),
                    secondPlan,
                    listOf(secondGrammar.toFile()),
                ),
            )
            assertFalse(
                "A flat preview directory must be regenerated when code-generation options change",
                session.hasReusableSources(
                    output.toFile(),
                    changedPackagePlan,
                    listOf(firstGrammar.toFile()),
                ),
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    fun `test generation snapshot is rejected when a dependency changes during generation`() {
        val root = Files.createTempDirectory("antlr4plus-preview-generation-race-")
        try {
            val grammar = root.resolve("Demo.g4")
            val imported = root.resolve("Delegate.g4")
            Files.writeString(
                grammar,
                "parser grammar Demo; import Delegate; start: delegated EOF;",
            )
            Files.writeString(imported, "parser grammar Delegate; delegated: 'before';")
            val output = Files.createDirectories(root.resolve("output"))
            val plan =
                PreviewGeneratedArtifactPolicy.buildPlan(
                    parserGrammarFile = grammar.toFile(),
                    lexerGrammarFile = null,
                    charset = Charsets.UTF_8,
                    sourceExtension = "java",
                    generateListener = false,
                    generateVisitor = false,
                )
            plan.requiredSourceNames.forEach { name ->
                Files.writeString(output.resolve(name), name)
            }
            val inputs = listOf(grammar.toFile(), imported.toFile())
            val capturedBeforeGeneration =
                requireNotNull(
                    PreviewGeneratedArtifactPolicy.captureDependencySnapshot(plan, inputs),
                )

            Files.writeString(imported, "parser grammar Delegate; delegated: 'after';")

            assertFalse(
                session.recordGeneration(
                    output.toFile(),
                    plan,
                    inputs,
                    capturedBeforeGeneration,
                ),
            )
            assertFalse(session.hasReusableSources(output.toFile(), plan, inputs))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    fun `test required source plan matches ANTLR output for imported split grammar`() {
        val root = Files.createTempDirectory("antlr4plus-preview-plan-")
        try {
            val lexer = root.resolve("ShapeLexer.g4")
            val delegate = root.resolve("ShapeDelegate.g4")
            val parser = root.resolve("ShapeParser.g4")
            val output = Files.createDirectories(root.resolve("output"))
            Files.writeString(
                lexer,
                "lexer grammar ShapeLexer; ID: [a-z]+; WS: [ \\t\\r\\n]+ -> skip;",
            )
            Files.writeString(delegate, "parser grammar ShapeDelegate; delegated: ID;")
            Files.writeString(
                parser,
                """
                parser grammar ShapeParser;
                options { tokenVocab=ShapeLexer; }
                import ShapeDelegate;
                start: delegated EOF;
                """
                    .trimIndent(),
            )

            val tool =
                Tool(
                    arrayOf(
                        "-Dlanguage=Java",
                        "-listener",
                        "-visitor",
                        "-lib",
                        root.toString(),
                        "-o",
                        output.toString(),
                        parser.toString(),
                        lexer.toString(),
                    ),
                )
            tool.processGrammarsOnCommandLine()
            assertEquals(0, tool.numErrors)

            val plan =
                PreviewGeneratedArtifactPolicy.buildPlan(
                    parserGrammarFile = parser.toFile(),
                    lexerGrammarFile = lexer.toFile(),
                    charset = Charsets.UTF_8,
                    sourceExtension = "java",
                    generateListener = true,
                    generateVisitor = true,
                )
            val actual =
                output.toFile().listFiles()?.filter { it.isFile }?.mapTo(mutableSetOf()) { it.name } ?: emptySet()
            assertTrue(
                "Required plan contains files ANTLR did not emit. Required=${plan.requiredSourceNames}, actual=$actual",
                actual.containsAll(plan.requiredSourceNames),
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    fun `test regeneration cleanup never follows workspace symlinks`() {
        val root = Files.createTempDirectory("antlr4plus-preview-symlink-clean-")
        try {
            val grammar = root.resolve("Demo.g4")
            Files.writeString(grammar, "grammar Demo; start: EOF;")
            val output = Files.createDirectories(root.resolve("output"))
            val plan =
                PreviewGeneratedArtifactPolicy.buildPlan(
                    parserGrammarFile = grammar.toFile(),
                    lexerGrammarFile = null,
                    charset = Charsets.UTF_8,
                    sourceExtension = "java",
                    generateListener = true,
                    generateVisitor = true,
                )

            val externalCompiled = Files.createDirectories(root.resolve("external-compiled"))
            val externalClass = Files.writeString(externalCompiled.resolve("Keep.class"), "outside")
            Files.createSymbolicLink(output.resolve("compiled"), externalCompiled)

            val externalMetadata = Files.createDirectories(root.resolve("external-metadata"))
            val externalMap =
                Files.writeString(
                    externalMetadata.resolve("DemoParser.java.map"),
                    "outside map",
                )
            val externalSnapshot =
                Files.writeString(
                    externalMetadata.resolve("dependencies.sha256"),
                    "outside snapshot",
                )
            Files.createSymbolicLink(output.resolve(".antlr4plus"), externalMetadata)

            PreviewGeneratedArtifactPolicy.cleanForRegeneration(output.toFile(), plan)

            assertTrue(
                "Compiled cleanup followed a symlink outside the workspace",
                Files.exists(externalClass),
            )
            assertTrue(
                "Map cleanup followed a symlink outside the workspace",
                Files.exists(externalMap),
            )
            assertTrue(
                "Snapshot cleanup followed a symlink outside the workspace",
                Files.exists(externalSnapshot),
            )
            assertFalse(
                "The unsafe compiled symlink should be removed",
                Files.exists(output.resolve("compiled"), LinkOption.NOFOLLOW_LINKS),
            )
            assertFalse(
                "The unsafe metadata symlink should be removed",
                Files.exists(output.resolve(".antlr4plus"), LinkOption.NOFOLLOW_LINKS),
            )
        } finally {
            Files.deleteIfExists(root.resolve("output/compiled"))
            Files.deleteIfExists(root.resolve("output/.antlr4plus"))
            root.toFile().deleteRecursively()
        }
    }

    fun `test cache reuse rejects generated source symlinks`() {
        val root = Files.createTempDirectory("antlr4plus-preview-symlink-source-")
        try {
            val grammar = root.resolve("Demo.g4")
            Files.writeString(grammar, "grammar Demo; start: EOF;")
            val output = Files.createDirectories(root.resolve("output"))
            val plan =
                PreviewGeneratedArtifactPolicy.buildPlan(
                    parserGrammarFile = grammar.toFile(),
                    lexerGrammarFile = null,
                    charset = Charsets.UTF_8,
                    sourceExtension = "java",
                    generateListener = false,
                    generateVisitor = false,
                )

            plan.requiredSourceNames.forEach { name ->
                Files.writeString(output.resolve(name), name)
            }
            assertTrue(
                session.recordGeneration(
                    output.toFile(),
                    plan,
                    listOf(grammar.toFile()),
                ),
            )

            val linkedName = plan.requiredSourceNames.first()
            Files.delete(output.resolve(linkedName))
            val externalSource = Files.writeString(root.resolve("external-$linkedName"), "external source")
            Files.createSymbolicLink(output.resolve(linkedName), externalSource)

            assertFalse(
                "A symlinked generated source must never make the preview cache reusable",
                session.hasReusableSources(
                    output.toFile(),
                    plan,
                    listOf(grammar.toFile()),
                ),
            )
            assertFalse(
                "Symlinked sources must not be returned to the compiler",
                PreviewGeneratedArtifactPolicy.requiredSourcesIn(output.toFile(), plan).any {
                    it.name == linkedName
                },
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    fun `test dependency fingerprints are session-only and never written to output`() {
        val root = Files.createTempDirectory("antlr4plus-preview-session-cache-")
        try {
            val grammar = root.resolve("Demo.g4")
            Files.writeString(grammar, "grammar Demo; start: EOF;")
            val output = Files.createDirectories(root.resolve("output"))
            val plan =
                PreviewGeneratedArtifactPolicy.buildPlan(
                    parserGrammarFile = grammar.toFile(),
                    lexerGrammarFile = null,
                    charset = Charsets.UTF_8,
                    sourceExtension = "java",
                    generateListener = false,
                    generateVisitor = false,
                )
            plan.requiredSourceNames.forEach { name ->
                Files.writeString(output.resolve(name), name)
            }
            val inputs = listOf(grammar.toFile())

            assertTrue(session.recordGeneration(output.toFile(), plan, inputs))
            assertTrue(session.hasReusableSources(output.toFile(), plan, inputs))
            assertFalse(Files.exists(output.resolve(".antlr4plus"), LinkOption.NOFOLLOW_LINKS))

            val restartedSession = PreviewGeneratedArtifactPolicy.SessionCache()
            assertFalse(
                "A new project session must regenerate before trusting existing output",
                restartedSession.hasReusableSources(output.toFile(), plan, inputs),
            )

            session.clear()
            assertFalse(session.hasReusableSources(output.toFile(), plan, inputs))
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
