package com.antlr4plus.codeGen

import com.antlr4plus.parsing.ANTLRToolListenerImpl
import com.antlr4plus.parsing.ParsingUtils
import com.antlr4plus.previewService.PreviewService
import com.antlr4plus.settings.GrammarProperties
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertFails
import org.antlr.v4.Tool

/** Tests target/listener/visitor/output-subdirectory permutations and representative free-text settings. */
class UserGenerationPermutationTest : BasePlatformTestCase() {

    private lateinit var diskFixtureRoot: Path

    override fun setUp() {
        super.setUp()
        diskFixtureRoot = Files.createTempDirectory("antlr4plus-generation-permutations-")
    }

    override fun tearDown() {
        try {
            diskFixtureRoot.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    private val targetExtensions =
        mapOf(
            "Cpp" to setOf("cpp", "h"),
            "CSharp" to setOf("cs"),
            "Dart" to setOf("dart"),
            "Go" to setOf("go"),
            "Java" to setOf("java"),
            "JavaScript" to setOf("js"),
            "Kotlin" to setOf("kt"),
            "PHP" to setOf("php"),
            "Python3" to setOf("py"),
            "Rust" to setOf("rs"),
            "Swift" to setOf("swift"),
            "TypeScript" to setOf("ts"),
        )

    fun `test every finite Generate control permutation produces the selected target`() {
        val files =
            configureProfile(
                "grammars/Matrix.g4",
                """
                grammar Matrix;
                start: ID EOF;
                ID: [a-zA-Z]+;
                WS: [ \t\r\n]+ -> skip;
                """
                    .trimIndent(),
            )
        var permutation = 0

        for (language in GenAntlrCode.SUPPORTED_TARGET_LANGUAGES) {
            for (listener in listOf(false, true)) {
                for (visitor in listOf(false, true)) {
                    for (languageSubdir in listOf(false, true)) {
                        val label = "$language listener=$listener visitor=$visitor langDir=$languageSubdir"
                        val outputRoot = "matrix/${permutation++}"
                        val generated =
                            generate(
                                files,
                                language = language,
                                listener = listener,
                                visitor = visitor,
                                languageSubdir = languageSubdir,
                                outputDir = outputRoot,
                            )

                        val extensions = targetExtensions.getValue(language)
                        assertTrue(
                            "$label generated no target source files: $generated",
                            generated.any {
                                it.extension.lowercase() in extensions
                            },
                        )
                        assertEquals(
                            "$label listener output mismatch: $generated",
                            listener,
                            generated.any { "listener" in it.name.lowercase() },
                        )
                        assertEquals(
                            "$label visitor output mismatch: $generated",
                            visitor,
                            generated.any { "visitor" in it.name.lowercase() },
                        )

                        val outputArg = lastArguments.argumentAfter("-o")
                        val expectedSuffix =
                            if (languageSubdir) {
                                "$outputRoot${File.separator}$language"
                            } else {
                                outputRoot
                            }
                        assertTrue(
                            "$label output was $outputArg",
                            outputArg.endsWith(expectedSuffix),
                        )
                    }
                }
            }
        }

        assertEquals(96, permutation)
    }

    fun `test Rust generation emits only sources for the declared external runtime`() {
        val files =
            configureProfile(
                "rust/Input.g4",
                "grammar Input; start: ID EOF; ID: [a-z]+; WS: [ \\t\\r\\n]+ -> skip;",
                "rust/input.g4p",
            )

        val generated =
            generate(
                files,
                language = "Rust",
                listener = true,
                visitor = true,
                languageSubdir = true,
                outputDir = "rust-output",
            )
        val names = generated.mapTo(mutableSetOf(), File::getName)

        assertTrue(
            "Missing Rust target sources: $names",
            names.containsAll(
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
            generated
                .single { it.name == "inputparser.rs" }
                .readText()
                .contains("antlr4rust::const_check::version(\"0\", \"6\")"),
        )
        assertTrue(
            "Rust generation unexpectedly emitted executable or archive artifacts: $generated",
            generated.none {
                it.extension.lowercase() in setOf("class", "crate", "dll", "dylib", "exe", "jar", "so")
            },
        )
    }

    fun `test every target handles combined lexer parser and imported grammar shapes`() {
        val combined =
            configureProfile(
                "shapes/combined/Shape.g4",
                "grammar Shape; start: ID EOF; ID: [a-z]+; WS: [ \\t\\r\\n]+ -> skip;",
                "shapes/combined/input.g4p",
            )
        val lexerOnly =
            configureProfile(
                "shapes/lexer/ShapeLexer.g4",
                "lexer grammar ShapeLexer; ID: [a-z]+; WS: [ \\t\\r\\n]+ -> skip;",
                "shapes/lexer/input.g4p",
            )
        addGrammar(
            "shapes/split/ShapeLexer.g4",
            "lexer grammar ShapeLexer; ID: [a-z]+; WS: [ \\t\\r\\n]+ -> skip;",
        )
        val split =
            configureProfile(
                "shapes/split/ShapeParser.g4",
                "parser grammar ShapeParser; options { tokenVocab=ShapeLexer; } start: ID EOF;",
                "shapes/split/input.g4p",
            )
        addGrammar(
            "shapes/imported/ShapeDelegate.g4",
            "parser grammar ShapeDelegate; delegated: ID;",
        )
        addGrammar(
            "shapes/imported/ShapeLexer.g4",
            "lexer grammar ShapeLexer; ID: [a-z]+; WS: [ \\t\\r\\n]+ -> skip;",
        )
        val imported =
            configureProfile(
                "shapes/imported/ShapeParser.g4",
                """
                parser grammar ShapeParser;
                options { tokenVocab=ShapeLexer; }
                import ShapeDelegate;
                start: delegated EOF;
                """
                    .trimIndent(),
                "shapes/imported/input.g4p",
            )

        val shapes =
            listOf(
                "combined" to combined,
                "lexer" to lexerOnly,
                "split" to split,
                "imported" to imported,
            )
        for (language in GenAntlrCode.SUPPORTED_TARGET_LANGUAGES) {
            for ((shape, files) in shapes) {
                val generated =
                    generate(
                        files,
                        language = language,
                        listener = true,
                        visitor = true,
                        languageSubdir = true,
                        outputDir = "shape-output/$language/$shape",
                    )
                assertTrue("$language/$shape generated nothing", generated.isNotEmpty())
                assertTrue(
                    "$language/$shape generated no target source: $generated",
                    generated.any {
                        it.extension.lowercase() in targetExtensions.getValue(language)
                    },
                )
            }
        }
    }

    fun `test GenAntlrCode reports listener errors as failed generation`() {
        val files =
            configureProfile(
                "invalid/Broken.g4",
                "grammar Broken; start: missing EOF;",
                "invalid/input.g4p",
            )
        val settings = activate(files)
        settings.language = "Java"
        settings.outputDir = "invalid-output"

        val result = GenAntlrCode.antlr(project, files.grammar)

        assertFalse(result.successful)
        assertTrue("Expected the ANTLR diagnostic to be retained", result.errors.isNotEmpty())
        assertNull(result.failure)
    }

    fun `test Generate preserves unrelated source files in a shared output directory`() {
        val output = diskFixtureRoot.resolve("cleanup-policy")
        Files.createDirectories(output)
        val unrelated = Files.writeString(output.resolve("KeepMe.java"), "final class KeepMe {}")
        val staleGenerated = Files.writeString(output.resolve("CleanParser.java"), "stale")

        GenAntlrCode.cleanExpectedGeneratedFiles(
            output.toFile(),
            setOf("CleanParser.java", "CleanLexer.java"),
        )

        assertTrue("Generate deleted an unrelated user source file", Files.exists(unrelated))
        assertFalse("Stale output owned by the grammar was retained", Files.exists(staleGenerated))
    }

    fun `test malformed on-disk grammar reports errors without throwing`() {
        val grammar = diskFixtureRoot.resolve("invalid-on-disk/Broken.g4")
        val output = diskFixtureRoot.resolve("invalid-on-disk/output")
        Files.createDirectories(grammar.parent)
        Files.writeString(grammar, "grammar Broken; start: missing EOF;")
        val tool = Tool(arrayOf("-Dlanguage=Java", "-o", output.toString(), grammar.toString()))
        val toolListener = ANTLRToolListenerImpl(tool, null)
        tool.removeListeners()
        tool.addListener(toolListener)

        val thrown = runCatching { tool.processGrammarsOnCommandLine() }.exceptionOrNull()

        assertNull(thrown)
        assertTrue(toolListener.errors.isNotEmpty())
        assertTrue(tool.numErrors > 0)
    }

    fun `test default lib remains the grammar directory after output already exists`() {
        val files =
            configureProfile(
                "stable-lib/Stable.g4",
                "grammar Stable; start: ID EOF; ID: [a-z]+;",
                "stable-lib/input.g4p",
            )
        val settings = activate(files)
        settings.outputDir = "existing-output"
        File(files.profile.parent.path, "existing-output").mkdirs()

        val args = GenAntlrCode.getANTLRArgsAsList(project, files.grammar)

        assertEquals(files.grammar.parent.path, args.argumentAfter("-lib"))
    }

    fun `test every encoding offered by the UI reaches the ANTLR arguments`() {
        val files =
            configureProfile(
                "encodings/EncodingMatrix.g4",
                "grammar EncodingMatrix; start: EOF;",
                "encodings/input.g4p",
            )
        val settings = activate(files)

        for (encoding in Charset.availableCharsets().keys) {
            settings.encoding = encoding
            val args = GenAntlrCode.getANTLRArgsAsList(project, files.grammar)
            assertEquals(encoding, args.argumentAfter("-encoding"))
        }
    }

    fun `test representative encoded grammar files generate correctly`() {
        val encodings = listOf("UTF-8", "ISO-8859-1", "windows-1252", "UTF-16", "UTF-16LE", "UTF-16BE")
        for (encoding in encodings) {
            val charset = Charset.forName(encoding)
            val grammar = diskFixtureRoot.resolve("encoded/$encoding/Encoded.g4")
            val output = diskFixtureRoot.resolve("encoded/$encoding/output")
            Files.createDirectories(grammar.parent)
            Files.write(
                grammar,
                "grammar Encoded; start: VALUE EOF; VALUE: 'é';".toByteArray(charset),
            )
            val tool =
                Tool(
                    arrayOf(
                        "-Dlanguage=Java",
                        "-encoding",
                        encoding,
                        "-o",
                        output.toString(),
                        grammar.toString(),
                    ),
                )
            val listener = ANTLRToolListenerImpl(tool, null)
            tool.removeListeners()
            tool.addListener(listener)

            val thrown = runCatching { tool.processGrammarsOnCommandLine() }.exceptionOrNull()

            assertNull("$encoding threw $thrown", thrown)
            assertTrue(
                "$encoding errors: ${listener.errors}",
                listener.errors.isEmpty() && tool.numErrors == 0,
            )
            assertTrue(
                "$encoding generated no Java",
                output.toFile().walkTopDown().any { it.extension == "java" },
            )
            assertTrue(
                "$encoding could not be inspected by the plugin before generation",
                GenAntlrCode.computeExpectedAntlrOutputFiles(grammar.toFile(), null, charset)
                    .containsAll(setOf("EncodedParser.java", "EncodedLexer.java")),
            )
        }
    }

    fun `test selected encoding is used when discovering tokenVocab lexer grammars`() {
        for (encoding in listOf("UTF-16", "UTF-16LE", "UTF-16BE")) {
            val charset = Charset.forName(encoding)
            val directory = diskFixtureRoot.resolve("encoded-token-vocab/$encoding")
            Files.createDirectories(directory)
            val lexer = directory.resolve("EncodedLexer.g4")
            val parser = directory.resolve("EncodedParser.g4")
            Files.write(
                lexer,
                "lexer grammar EncodedLexer; ID: [a-z]+;".toByteArray(charset),
            )
            Files.write(
                parser,
                "parser grammar EncodedParser; options { tokenVocab=EncodedLexer; } start: ID EOF;"
                    .toByteArray(charset),
            )

            assertEquals(
                lexer.toFile().absolutePath,
                ParsingUtils.getLexerFileForParser(parser.toString(), charset),
            )
            assertTrue(
                GenAntlrCode.computeExpectedAntlrOutputFiles(
                        parser.toFile(),
                        lexer.toFile(),
                        charset,
                    )
                    .containsAll(setOf("EncodedParser.java", "EncodedLexer.java")),
            )
        }
    }

    fun `test every target accepts a valid nested package from UI settings`() {
        val files =
            configureProfile(
                "packages/Packaged.g4",
                "grammar Packaged; start: ID EOF; ID: [a-z]+;",
                "packages/input.g4p",
            )
        for (language in GenAntlrCode.SUPPORTED_TARGET_LANGUAGES) {
            val generated =
                generate(
                    files,
                    language = language,
                    listener = true,
                    visitor = true,
                    languageSubdir = true,
                    outputDir = "package-output/$language",
                    packageName = "example.generated",
                )
            assertTrue(
                "$language package generation produced no target source",
                generated.any {
                    it.extension.lowercase() in targetExtensions.getValue(language)
                },
            )
            assertEquals("example.generated", lastArguments.argumentAfter("-package"))
            assertTrue(
                lastArguments
                    .argumentAfter("-o")
                    .endsWith(
                        "package-output/$language/$language${File.separator}example${File.separator}generated",
                    ),
            )
        }
    }

    fun `test invalid package language and encoding settings fail before invoking ANTLR`() {
        val files =
            configureProfile(
                "validation/Validated.g4",
                "grammar Validated; start: EOF;",
                "validation/input.g4p",
            )
        val settings = activate(files)

        val validPackages = listOf("", "a", "_private", "com.Example_name.v2")
        validPackages.forEach { packageName ->
            settings.packageName = packageName
            GenAntlrCode.getANTLRArgsAsList(project, files.grammar)
        }

        val invalidPackages = listOf(".", "..", "../escape", "bad/name", "bad-name", "9bad", "a..b", "a;evil")
        invalidPackages.forEach { packageName ->
            settings.packageName = packageName
            assertFails("Package should be rejected: $packageName") {
                GenAntlrCode.getANTLRArgsAsList(project, files.grammar)
            }
        }

        settings.packageName = ""
        settings.language = "Ruby"
        assertFails("Unsupported target should be rejected") {
            GenAntlrCode.getANTLRArgsAsList(project, files.grammar)
        }
        settings.language = "Java"
        settings.encoding = "definitely-not-a-charset"
        assertFails("Unsupported encoding should be rejected") {
            GenAntlrCode.getANTLRArgsAsList(project, files.grammar)
        }

        val result = GenAntlrCode.antlr(project, files.grammar)
        assertFalse(result.successful)
        assertTrue(result.failure is IllegalArgumentException)
    }

    fun `test output and lib free-text paths stay within project boundaries`() {
        val files =
            configureProfile(
                "paths/PathMatrix.g4",
                "grammar PathMatrix; start: EOF;",
                "paths/input.g4p",
            )
        val settings = activate(files)
        settings.language = "Java"
        settings.packageName = ""
        settings.encoding = "UTF-8"
        val contentRoot = files.profile.parent

        val outputCases =
            listOf(
                null to "${contentRoot.path}/gen/Java",
                "gen" to "${contentRoot.path}/gen/Java",
                "nested/out" to "${contentRoot.path}/nested/out/Java",
                "${project.basePath}/absolute-out" to "${project.basePath}/absolute-out/Java",
                "../../escape" to "${contentRoot.path}/gen/Java",
                "/tmp/antlr4plus-outside-project" to "${contentRoot.path}/gen/Java",
            )
        outputCases.forEach { (configured, expected) ->
            settings.outputDir = configured
            assertEquals(
                expected,
                GenAntlrCode.getANTLRArgsAsList(project, files.grammar).argumentAfter("-o"),
            )
        }

        settings.outputDir = "gen"
        val sourcePath = files.grammar.parent.path
        val libCases =
            listOf(
                "" to sourcePath,
                "vocab" to "$sourcePath${File.separator}vocab",
                "${project.basePath}/shared-vocab" to "${project.basePath}/shared-vocab",
            )
        libCases.forEach { (configured, expected) ->
            settings.libDir = configured
            assertEquals(
                expected,
                GrammarProperties.resolveLibDir(
                    project.getService(PreviewService::class.java).state,
                    project,
                    sourcePath,
                ),
            )
        }
        for (outside in listOf("../../outside", "/tmp/antlr4plus-outside-project")) {
            settings.libDir = outside
            assertNull(
                GrammarProperties.resolveLibDir(
                    project.getService(PreviewService::class.java).state,
                    project,
                    sourcePath,
                ),
            )
        }

        settings.libDir = ""
        settings.outputDir = ""
        assertEquals(
            "${contentRoot.path}/gen/Java",
            GenAntlrCode.getANTLRArgsAsList(project, files.grammar).argumentAfter("-o"),
        )

        settings.outputDir = "/tmp/antlr4plus-preview-outside-project"
        assertEquals(
            "${contentRoot.path}/gen/preview/java",
            GrammarProperties.resolvePreviewOutputDir(
                    project.getService(PreviewService::class.java).state,
                    project,
                    contentRoot,
                    "Java",
                )
                .replace(File.separatorChar, '/'),
        )
        assertEquals(
            sourcePath,
            GrammarProperties.resolveLibDir(
                project.getService(PreviewService::class.java).state,
                project,
                sourcePath,
            ),
        )
    }

    fun `test preview bindings share one replaceable directory per runtime`() {
        val first =
            configureProfile(
                "isolated/first/Demo.g4",
                "grammar Demo; start: EOF;",
                "isolated/profiles/first.g4p",
            )
        val second =
            configureProfile(
                "isolated/second/Demo.g4",
                "grammar Demo; start: EOF;",
                "isolated/profiles/second.g4p",
            )

        activate(first).outputDir = "gen"
        val firstOutput =
            GrammarProperties.resolvePreviewOutputDir(
                project.getService(PreviewService::class.java).state,
                project,
                first.profile.parent,
                "Java",
            )

        activate(second).outputDir = "gen"
        val secondOutput =
            GrammarProperties.resolvePreviewOutputDir(
                project.getService(PreviewService::class.java).state,
                project,
                second.profile.parent,
                "Java",
            )

        val kotlinOutput =
            GrammarProperties.resolvePreviewOutputDir(
                project.getService(PreviewService::class.java).state,
                project,
                second.profile.parent,
                "Kotlin",
            )

        assertEquals(firstOutput, secondOutput)
        assertTrue(firstOutput.replace(File.separatorChar, '/').endsWith("/gen/preview/java"))
        assertTrue(kotlinOutput.replace(File.separatorChar, '/').endsWith("/gen/preview/kotlin"))
    }

    fun `test Delete output policy rejects roots outside paths and directories containing inputs`() {
        val files =
            configureProfile(
                "delete-policy/grammars/Protected.g4",
                "grammar Protected; start: EOF;",
                "delete-policy/profiles/input.g4p",
            )
        val settings = activate(files)
        val protectedPaths = listOf(files.profile.path, files.grammar.path)

        fun resolve(configured: String?): File? {
            settings.outputDir = configured
            return GrammarProperties.resolveOutputDirForDeletion(
                project.getService(PreviewService::class.java).state,
                project,
                files.profile.parent,
                protectedPaths,
            )
        }

        assertEquals(File(files.profile.parent.path, "gen").canonicalFile, resolve(null))
        assertEquals(
            File(files.profile.parent.path, "nested/output").canonicalFile,
            resolve("nested/output"),
        )
        assertEquals(
            File(project.basePath!!, "safe-generated").canonicalFile,
            resolve("${project.basePath}/safe-generated"),
        )

        assertNull(resolve("."))
        assertNull(resolve(".."))
        assertNull(resolve("../grammars"))
        assertNull(resolve("../../../outside"))
        assertNull(resolve(project.basePath))
        assertNull(resolve("/tmp/antlr4plus-delete-outside-project"))
    }

    private lateinit var lastArguments: List<String>

    private fun generate(
        files: ProfileFiles,
        language: String,
        listener: Boolean,
        visitor: Boolean,
        languageSubdir: Boolean,
        outputDir: String,
        packageName: String = "",
    ): List<File> {
        val settings = activate(files)
        settings.language = language
        settings.generateListener = listener
        settings.generateVisitor = visitor
        settings.createLanguageSubdir = languageSubdir
        settings.outputDir = outputDir
        settings.libDir = ""
        settings.packageName = packageName
        settings.encoding = "UTF-8"

        val arguments = GenAntlrCode.getANTLRArgsAsList(project, files.grammar)
        lastArguments = arguments.toList()

        val diskOutput =
            diskFixtureRoot.resolve("generated").resolve(outputDir).let {
                if (languageSubdir) it.resolve(language) else it
            }
        arguments[arguments.indexOf("-o") + 1] = diskOutput.toString()
        arguments[arguments.indexOf("-lib") + 1] = files.diskGrammar.parent.toString()
        arguments += files.diskGrammar.toString()
        ParsingUtils.getLexerFileForParser(files.diskGrammar.toString())?.let(arguments::add)

        val tool = Tool(arguments.toTypedArray())
        val toolListener = ANTLRToolListenerImpl(tool, null)
        tool.removeListeners()
        tool.addListener(toolListener)
        val thrown = runCatching { tool.processGrammarsOnCommandLine() }.exceptionOrNull()

        assertNull("ANTLR threw for ${arguments.joinToString(" ")}", thrown)
        assertTrue(
            "ANTLR errors for ${arguments.joinToString(" ")}:\n${toolListener.errors.joinToString("\n")}",
            toolListener.errors.isEmpty() && tool.numErrors == 0,
        )

        val output = diskOutput.toFile()
        return output.walkTopDown().filter(File::isFile).toList()
    }

    private fun configureProfile(
        grammarPath: String,
        grammarText: String,
        profilePath: String = "input.g4p",
    ): ProfileFiles {
        val grammar = addGrammar(grammarPath, grammarText)
        val profile = myFixture.addFileToProject(profilePath, "example").virtualFile
        return ProfileFiles(grammar, profile, diskFixtureRoot.resolve(grammarPath))
    }

    private fun addGrammar(
        grammarPath: String,
        grammarText: String,
    ): com.intellij.openapi.vfs.VirtualFile {
        val diskGrammar = diskFixtureRoot.resolve(grammarPath)
        Files.createDirectories(diskGrammar.parent)
        Files.writeString(diskGrammar, grammarText)
        return myFixture.addFileToProject(grammarPath, grammarText).virtualFile
    }

    private fun activate(files: ProfileFiles) =
        project.getService(PreviewService::class.java).state.run {
            g4pInputFile = files.profile
            grammarFile = files.grammar
            requireNotNull(liveSettings)
        }

    private fun List<String>.argumentAfter(flag: String): String {
        val index = indexOf(flag)
        assertTrue("Missing $flag in ${joinToString(" ")}", index >= 0 && index + 1 < size)
        return this[index + 1]
    }

    private data class ProfileFiles(
        val grammar: com.intellij.openapi.vfs.VirtualFile,
        val profile: com.intellij.openapi.vfs.VirtualFile,
        val diskGrammar: Path,
    )
}
