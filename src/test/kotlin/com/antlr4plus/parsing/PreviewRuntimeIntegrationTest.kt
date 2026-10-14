package com.antlr4plus.parsing

import com.antlr4plus.compat.compatSetTrusted
import com.antlr4plus.error.ComposeRuntimeConsolePanel
import com.antlr4plus.previewService.PreviewService
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.antlr.v4.Tool

/** Exercises generation, compilation, class loading and parsing for both preview runtimes. */
class PreviewRuntimeIntegrationTest : BasePlatformTestCase() {

    private lateinit var fixtureRoot: Path
    private lateinit var grammarPath: Path

    override fun setUp() {
        super.setUp()
        fixtureRoot = Files.createTempDirectory("antlr4plus-preview-runtime-")
        grammarPath = fixtureRoot.resolve("RuntimeMatrix.g4")
        Files.writeString(
            grammarPath,
            """
            grammar RuntimeMatrix;
            start: item+ EOF;
            alternate: NUMBER EOF;
            item: WORD | NUMBER | UNICODE;
            WORD: [a-zA-Z]+;
            NUMBER: [0-9]+;
            UNICODE: [\u0080-\uFFFF]+;
            WS: [ \t\r\n]+ -> skip;
            """
                .trimIndent(),
        )
        Files.writeString(fixtureRoot.resolve("input.g4p"), "alpha")
    }

    override fun tearDown() {
        try {
            if (::grammarPath.isInitialized) {
                CompiledParsingService.invalidateCache(grammarPath.toString())
            }
            fixtureRoot.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    fun `test both preview runtimes parse every input and case permutation`() = runBlocking {
        val localFileSystem = LocalFileSystem.getInstance()
        val grammarFile = requireNotNull(localFileSystem.refreshAndFindFileByNioFile(grammarPath))
        val profileFile =
            requireNotNull(
                localFileSystem.refreshAndFindFileByNioFile(fixtureRoot.resolve("input.g4p")),
            )
        val previewService = project.getService(PreviewService::class.java)
        previewService.state.g4pInputFile = profileFile
        previewService.state.grammarFile = grammarFile
        previewService.state.liveSettings?.libDir = ""

        val loaded = requireNotNull(ParsingUtils.loadGrammars(project, grammarFile))
        val lexerGrammar = requireNotNull(loaded.first)
        val parserGrammar = requireNotNull(loaded.second)
        previewService.state.lexerGrammar = lexerGrammar
        previewService.state.grammar = parserGrammar

        val inputs =
            linkedMapOf(
                "valid" to ("alpha 123 café" to false),
                "multiline" to ("alpha\n123\r\nbeta" to false),
                "unicode" to ("世界" to false),
                "empty" to ("" to true),
                "invalid character" to ("alpha @ beta" to true),
                "large" to (List(5_000) { "word" }.joinToString(" ") to false),
            )

        for (runtime in listOf("Java", "Kotlin")) {
            val generatedDirectory = generatePreviewSources(runtime)
            previewService.state.liveSettings?.previewRuntime = runtime

            for (strategy in CaseChangingStrategy.values()) {
                previewService.state.liveSettings?.caseChangingStrategy = strategy
                for ((label, inputCase) in inputs) {
                    val (input, expectErrors) = inputCase
                    val result =
                        CompiledParsingService.parseWithCompiledClasses(
                            parserGrammar,
                            lexerGrammar,
                            "start",
                            grammarFile,
                            input,
                            generatedDirectory.toFile(),
                            project,
                        )
                    assertTrue(
                        "$runtime/$strategy/$label failed: $result",
                        result is CompiledParsingService.CompiledParseResult.Success,
                    )
                    val parsingResult = (result as CompiledParsingService.CompiledParseResult.Success).result
                    assertEquals(
                        "$runtime runtime mismatch",
                        runtime == "Kotlin",
                        parsingResult.isKotlin,
                    )
                    val errors = parsingResult.syntaxErrorListener?.syntaxErrors.orEmpty()
                    assertEquals(
                        "$runtime/$strategy/$label diagnostics=$errors",
                        expectErrors,
                        errors.isNotEmpty(),
                    )
                }
            }

            val explicitRule =
                CompiledParsingService.parseWithCompiledClasses(
                    parserGrammar,
                    lexerGrammar,
                    "alternate",
                    grammarFile,
                    "123",
                    generatedDirectory.toFile(),
                    project,
                )
            assertTrue(
                "$runtime explicit start rule failed",
                explicitRule is CompiledParsingService.CompiledParseResult.Success,
            )

            val missingRule =
                CompiledParsingService.parseWithCompiledClasses(
                    parserGrammar,
                    lexerGrammar,
                    "missingRule",
                    grammarFile,
                    "123",
                    generatedDirectory.toFile(),
                    project,
                )
            assertTrue(
                "$runtime missing start rule must fail cleanly",
                missingRule is CompiledParsingService.CompiledParseResult.Failed,
            )
        }
    }

    fun `test action exceptions from both preview runtimes fail without escaping`() = runBlocking {
        val profileFile =
            requireNotNull(
                LocalFileSystem.getInstance().refreshAndFindFileByNioFile(fixtureRoot.resolve("input.g4p")),
            )
        val previewService = project.getService(PreviewService::class.java)
        previewService.state.g4pInputFile = profileFile

        val grammars =
            mapOf(
                "Java" to
                    """
                    grammar ThrowingJava;
                    @parser::members { void explode() { throw new IllegalStateException("java boom"); } }
                    start: WORD {explode();} EOF;
                    WORD: [a-zA-Z]+;
                    WS: [ \t\r\n]+ -> skip;
                    """
                        .trimIndent(),
                "Kotlin" to
                    """
                    grammar ThrowingKotlin;
                    @parser::members { fun explode(): Unit { throw IllegalStateException("kotlin boom") } }
                    start: WORD {explode()} EOF;
                    WORD: [a-zA-Z]+;
                    WS: [ \t\r\n]+ -> skip;
                    """
                        .trimIndent(),
            )

        for ((runtime, grammarText) in grammars) {
            val path = fixtureRoot.resolve("Throwing$runtime.g4")
            Files.writeString(path, grammarText)
            val grammarFile = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path))
            previewService.state.grammarFile = grammarFile
            previewService.state.liveSettings?.previewRuntime = runtime
            val (lexerGrammar, parserGrammar) = requireNotNull(ParsingUtils.loadGrammars(project, grammarFile))
            previewService.state.lexerGrammar = requireNotNull(lexerGrammar)
            previewService.state.grammar = requireNotNull(parserGrammar)
            val generatedDirectory = generatePreviewSources(runtime, path)

            val call = runCatching {
                CompiledParsingService.parseWithCompiledClasses(
                    parserGrammar,
                    lexerGrammar,
                    "start",
                    grammarFile,
                    "hello",
                    generatedDirectory.toFile(),
                    project,
                )
            }

            assertTrue(
                "$runtime action exception escaped: ${call.exceptionOrNull()}",
                call.isSuccess,
            )
            assertTrue(
                "$runtime action exception must produce a failed preview",
                call.getOrThrow() is CompiledParsingService.CompiledParseResult.Failed,
            )
            CompiledParsingService.invalidateCache(path.toString())
        }
    }

    fun `test invalidating a running Java action defers classloader close and cancellation interrupts it`() =
        runBlocking {
            val path = fixtureRoot.resolve("BlockingJava.g4")
            Files.writeString(
                path,
                """
                grammar BlockingJava;
                @parser::members {
                    public static volatile boolean actionStarted = false;
                    public static volatile boolean actionInterrupted = false;
                    public static volatile boolean actionCompleted = false;
                }
                start: value=WORD {
                    if ("block".equals(${'$'}value.text)) {
                        actionStarted = true;
                        try {
                            Thread.sleep(60000L);
                        } catch (InterruptedException interrupted) {
                            actionInterrupted = true;
                            Thread.currentThread().interrupt();
                        }
                    }
                    if ("stubborn".equals(${'$'}value.text)) {
                        actionStarted = true;
                        long deadline = System.nanoTime() + 1500000000L;
                        while (System.nanoTime() < deadline) {
                            if (Thread.interrupted()) actionInterrupted = true;
                        }
                        actionCompleted = true;
                    }
                } EOF;
                WORD: [a-zA-Z]+;
                WS: [ \t\r\n]+ -> skip;
                """
                    .trimIndent(),
            )

            val localFileSystem = LocalFileSystem.getInstance()
            val grammarFile = requireNotNull(localFileSystem.refreshAndFindFileByNioFile(path))
            val profileFile =
                requireNotNull(
                    localFileSystem.refreshAndFindFileByNioFile(fixtureRoot.resolve("input.g4p")),
                )
            val previewService = project.getService(PreviewService::class.java)
            previewService.state.g4pInputFile = profileFile
            previewService.state.grammarFile = grammarFile
            previewService.state.liveSettings?.previewRuntime = "Java"
            val (lexerGrammar, parserGrammar) = requireNotNull(ParsingUtils.loadGrammars(project, grammarFile))
            previewService.state.lexerGrammar = requireNotNull(lexerGrammar)
            previewService.state.grammar = requireNotNull(parserGrammar)
            val generatedDirectory = generatePreviewSources("Java", path)

            val warmup =
                CompiledParsingService.parseWithCompiledClasses(
                    parserGrammar,
                    lexerGrammar,
                    "start",
                    grammarFile,
                    "fast",
                    generatedDirectory.toFile(),
                    project,
                )
            assertTrue(
                "Warmup parse failed: $warmup",
                warmup is CompiledParsingService.CompiledParseResult.Success,
            )
            val parserClass =
                requireNotNull(
                        (warmup as CompiledParsingService.CompiledParseResult.Success).result.parser,
                    )
                    .javaClass
            val startedField = parserClass.getField("actionStarted")
            val interruptedField = parserClass.getField("actionInterrupted")

            val blocked =
                async(Dispatchers.Default) {
                    CompiledParsingService.parseWithCompiledClasses(
                        parserGrammar,
                        lexerGrammar,
                        "start",
                        grammarFile,
                        "block",
                        generatedDirectory.toFile(),
                        project,
                    )
                }

            withTimeout(5_000L) {
                while (!startedField.getBoolean(null)) delay(10L)
            }
            CompiledParsingService.invalidateCache(path.toString())
            withTimeout(2_000L) { blocked.cancelAndJoin() }
            withTimeout(2_000L) {
                while (!interruptedField.getBoolean(null)) delay(10L)
            }

            val resumed =
                CompiledParsingService.parseWithCompiledClasses(
                    parserGrammar,
                    lexerGrammar,
                    "start",
                    grammarFile,
                    "fast",
                    generatedDirectory.toFile(),
                    project,
                )
            assertTrue(
                "Parse did not recover after cancellation/invalidation: $resumed",
                resumed is CompiledParsingService.CompiledParseResult.Success,
            )

            // Cancellation can finish the Future before user code exits. Retain its loader lease,
            // and ensure delayed cleanup cannot delete a replacement compilation.
            val resumedParserClass =
                requireNotNull(
                        (resumed as CompiledParsingService.CompiledParseResult.Success).result.parser,
                    )
                    .javaClass
            val stubbornStarted = resumedParserClass.getField("actionStarted")
            val stubbornCompleted = resumedParserClass.getField("actionCompleted")
            val stubborn =
                async(Dispatchers.Default) {
                    CompiledParsingService.parseWithCompiledClasses(
                        parserGrammar,
                        lexerGrammar,
                        "start",
                        grammarFile,
                        "stubborn",
                        generatedDirectory.toFile(),
                        project,
                    )
                }
            withTimeout(5_000L) {
                while (!stubbornStarted.getBoolean(null)) delay(10L)
            }
            CompiledParsingService.clearCache()
            withTimeout(2_000L) { stubborn.cancelAndJoin() }

            val replacement =
                CompiledParsingService.parseWithCompiledClasses(
                    parserGrammar,
                    lexerGrammar,
                    "start",
                    grammarFile,
                    "fast",
                    generatedDirectory.toFile(),
                    project,
                )
            assertTrue(
                "Replacement parse failed while retired action was exiting: $replacement",
                replacement is CompiledParsingService.CompiledParseResult.Success,
            )
            withTimeout(5_000L) {
                while (!stubbornCompleted.getBoolean(null)) delay(10L)
            }
            assertTrue(
                "Retired-loader cleanup deleted the replacement compilation",
                Files.isRegularFile(
                    generatedDirectory.resolve("compiled/BlockingJavaParser.class"),
                ),
            )

            val afterCleanup =
                CompiledParsingService.parseWithCompiledClasses(
                    parserGrammar,
                    lexerGrammar,
                    "start",
                    grammarFile,
                    "fast",
                    generatedDirectory.toFile(),
                    project,
                )
            assertTrue(
                "Parse failed after retired-loader cleanup: $afterCleanup",
                afterCleanup is CompiledParsingService.CompiledParseResult.Success,
            )
        }

    fun `test both preview runtimes compile and reload packaged classes`() = runBlocking {
        val localFileSystem = LocalFileSystem.getInstance()
        val grammarFile = requireNotNull(localFileSystem.refreshAndFindFileByNioFile(grammarPath))
        val profileFile =
            requireNotNull(
                localFileSystem.refreshAndFindFileByNioFile(fixtureRoot.resolve("input.g4p")),
            )
        val previewService = project.getService(PreviewService::class.java)
        previewService.state.g4pInputFile = profileFile
        previewService.state.grammarFile = grammarFile
        previewService.state.liveSettings?.libDir = ""
        previewService.state.liveSettings?.packageName = "example.preview"

        val loaded = requireNotNull(ParsingUtils.loadGrammars(project, grammarFile))
        val lexerGrammar = requireNotNull(loaded.first)
        val parserGrammar = requireNotNull(loaded.second)
        previewService.state.lexerGrammar = lexerGrammar
        previewService.state.grammar = parserGrammar

        for (runtime in listOf("Java", "Kotlin")) {
            val generatedDirectory = generatePreviewSources(runtime, packageName = "example.preview")
            previewService.state.liveSettings?.previewRuntime = runtime

            val first =
                CompiledParsingService.parseWithCompiledClasses(
                    parserGrammar,
                    lexerGrammar,
                    "start",
                    grammarFile,
                    "alpha 123",
                    generatedDirectory.toFile(),
                    project,
                )
            assertTrue(
                "$runtime packaged preview failed after compilation: $first",
                first is CompiledParsingService.CompiledParseResult.Success,
            )
            val expectedParserClass = "example.preview.RuntimeMatrixParser"
            assertEquals(expectedParserClass, parserBinaryName(first))

            val packagedClassDirectory = generatedDirectory.resolve("compiled/example/preview")
            assertTrue(
                "$runtime compiler did not emit package directories below compiled/",
                Files.isDirectory(packagedClassDirectory),
            )

            val compiledClassDirectory = packagedClassDirectory
            val lexerClass = compiledClassDirectory.resolve("RuntimeMatrixLexer.class")
            val parserClass = compiledClassDirectory.resolve("RuntimeMatrixParser.class")
            val cacheSentinelTime = FileTime.fromMillis(System.currentTimeMillis() + 60_000)
            Files.setLastModifiedTime(lexerClass, cacheSentinelTime)
            Files.setLastModifiedTime(parserClass, cacheSentinelTime)

            CompiledParsingService.invalidateCache(grammarPath.toString())
            val reloaded =
                CompiledParsingService.parseWithCompiledClasses(
                    parserGrammar,
                    lexerGrammar,
                    "start",
                    grammarFile,
                    "beta 456",
                    generatedDirectory.toFile(),
                    project,
                )
            assertTrue(
                "$runtime packaged preview could not reload trusted classes: $reloaded",
                reloaded is CompiledParsingService.CompiledParseResult.Success,
            )
            assertEquals(expectedParserClass, parserBinaryName(reloaded))
            assertEquals(
                "$runtime lexer was recompiled instead of reused",
                cacheSentinelTime,
                Files.getLastModifiedTime(lexerClass),
            )
            assertEquals(
                "$runtime parser was recompiled instead of reused",
                cacheSentinelTime,
                Files.getLastModifiedTime(parserClass),
            )
            project.compatSetTrusted(false)
            try {
                val blocked =
                    CompiledParsingService.parseWithCompiledClasses(
                        parserGrammar,
                        lexerGrammar,
                        "start",
                        grammarFile,
                        "beta 456",
                        generatedDirectory.toFile(),
                        project,
                    )
                assertEquals(
                    "$runtime cached code bypassed project trust",
                    CompiledParsingService.CompiledParseResult.GrammarCodeUntrusted,
                    blocked,
                )
            } finally {
                project.compatSetTrusted(true)
            }
            CompiledParsingService.invalidateCache(grammarPath.toString())
        }
    }

    fun `test Kotlin preview invalidates compiled cache when generated package changes`() = runBlocking {
        val localFileSystem = LocalFileSystem.getInstance()
        val grammarFile = requireNotNull(localFileSystem.refreshAndFindFileByNioFile(grammarPath))
        val profileFile =
            requireNotNull(
                localFileSystem.refreshAndFindFileByNioFile(fixtureRoot.resolve("input.g4p")),
            )
        val previewService = project.getService(PreviewService::class.java)
        previewService.state.g4pInputFile = profileFile
        previewService.state.grammarFile = grammarFile
        previewService.state.liveSettings?.libDir = ""
        previewService.state.liveSettings?.previewRuntime = "Kotlin"
        previewService.state.liveSettings?.packageName = "first.preview"

        val (lexerGrammar, parserGrammar) = requireNotNull(ParsingUtils.loadGrammars(project, grammarFile))
        previewService.state.lexerGrammar = requireNotNull(lexerGrammar)
        previewService.state.grammar = requireNotNull(parserGrammar)

        val generatedDirectory = generatePreviewSources("Kotlin", packageName = "first.preview")
        val first =
            CompiledParsingService.parseWithCompiledClasses(
                parserGrammar,
                lexerGrammar,
                "start",
                grammarFile,
                "alpha",
                generatedDirectory.toFile(),
                project,
            )
        assertTrue(
            "Initial packaged Kotlin preview failed: $first",
            first is CompiledParsingService.CompiledParseResult.Success,
        )
        assertEquals("first.preview.RuntimeMatrixParser", parserBinaryName(first))

        previewService.state.liveSettings?.packageName = "second.preview"
        generatePreviewSources("Kotlin", packageName = "second.preview")
        val sourceSentinelTime = FileTime.fromMillis(System.currentTimeMillis() + 120_000)
        val staleClassSentinelTime = FileTime.fromMillis(sourceSentinelTime.toMillis() + 60_000)
        Files.walk(generatedDirectory).use { paths ->
            paths
                .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".kt") }
                .forEach { Files.setLastModifiedTime(it, sourceSentinelTime) }
        }
        Files.walk(generatedDirectory.resolve("compiled")).use { paths ->
            paths
                .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".class") }
                .forEach { Files.setLastModifiedTime(it, staleClassSentinelTime) }
        }

        val second =
            CompiledParsingService.parseWithCompiledClasses(
                parserGrammar,
                lexerGrammar,
                "start",
                grammarFile,
                "beta",
                generatedDirectory.toFile(),
                project,
            )
        assertTrue(
            "Kotlin preview failed after package change: $second",
            second is CompiledParsingService.CompiledParseResult.Success,
        )
        assertEquals(
            "Stale parser class was reused after the generated package changed",
            "second.preview.RuntimeMatrixParser",
            parserBinaryName(second),
        )
    }

    fun `test completed preview releases its per grammar serialization state`() = runBlocking {
        val localFileSystem = LocalFileSystem.getInstance()
        val grammarFile = requireNotNull(localFileSystem.refreshAndFindFileByNioFile(grammarPath))
        val profileFile =
            requireNotNull(
                localFileSystem.refreshAndFindFileByNioFile(fixtureRoot.resolve("input.g4p")),
            )
        val previewService = project.getService(PreviewService::class.java)
        previewService.state.g4pInputFile = profileFile
        previewService.state.grammarFile = grammarFile
        previewService.state.liveSettings?.libDir = ""
        previewService.state.liveSettings?.previewRuntime = "Java"

        val (lexerGrammar, parserGrammar) = requireNotNull(ParsingUtils.loadGrammars(project, grammarFile))
        previewService.state.lexerGrammar = requireNotNull(lexerGrammar)
        previewService.state.grammar = requireNotNull(parserGrammar)
        val generatedDirectory = generatePreviewSources("Java")

        val results =
            (1..8)
                .map { index ->
                    async(Dispatchers.Default) {
                        CompiledParsingService.parseWithCompiledClasses(
                            parserGrammar,
                            lexerGrammar,
                            "start",
                            grammarFile,
                            "alpha $index",
                            generatedDirectory.toFile(),
                            project,
                        )
                    }
                }
                .awaitAll()

        assertTrue(
            "A concurrent preview failed before lifecycle verification: $results",
            results.all { it is CompiledParsingService.CompiledParseResult.Success },
        )
        assertFalse(
            "A completed preview retained permanent per-grammar serialization state",
            CompiledParsingService.hasGrammarMutexForTest(grammarPath.toString()),
        )
    }

    fun `test real Java and Kotlin actions preserve Unicode output in the runtime console`() = runBlocking {
        val profileFile =
            requireNotNull(
                LocalFileSystem.getInstance().refreshAndFindFileByNioFile(fixtureRoot.resolve("input.g4p")),
            )
        val previewService = project.getService(PreviewService::class.java)
        previewService.state.g4pInputFile = profileFile
        previewService.state.liveSettings?.libDir = ""
        val runtimeConsole = ComposeRuntimeConsolePanel(project)
        previewService.runtimeConsolePanel = runtimeConsole

        val grammars =
            linkedMapOf(
                "Java" to
                    """
                    grammar PrintingJava;
                    start: WORD {
                        System.out.println("java café 世界 🚀");
                        System.err.print("java trailing Ω");
                    } EOF;
                    WORD: [a-zA-Z]+;
                    WS: [ \t\r\n]+ -> skip;
                    """
                        .trimIndent(),
                "Kotlin" to
                    """
                    grammar PrintingKotlin;
                    start: WORD {
                        println("kotlin café 世界 🚀")
                        System.err.print("kotlin trailing Ω")
                    } EOF;
                    WORD: [a-zA-Z]+;
                    WS: [ \t\r\n]+ -> skip;
                    """
                        .trimIndent(),
            )

        for ((runtime, grammarText) in grammars) {
            val path = fixtureRoot.resolve("Printing$runtime.g4")
            Files.writeString(path, grammarText)
            val grammarFile = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path))
            previewService.state.grammarFile = grammarFile
            previewService.state.liveSettings?.previewRuntime = runtime
            val (lexerGrammar, parserGrammar) = requireNotNull(ParsingUtils.loadGrammars(project, grammarFile))
            previewService.state.lexerGrammar = requireNotNull(lexerGrammar)
            previewService.state.grammar = requireNotNull(parserGrammar)
            val generatedDirectory = generatePreviewSources(runtime, path)

            val result =
                CompiledParsingService.parseWithCompiledClasses(
                    parserGrammar,
                    lexerGrammar,
                    "start",
                    grammarFile,
                    "hello",
                    generatedDirectory.toFile(),
                    project,
                )

            assertTrue(
                "$runtime output grammar did not parse: $result",
                result is CompiledParsingService.CompiledParseResult.Success,
            )
            assertEquals(
                listOf(
                    "${runtime.lowercase()} café 世界 🚀",
                    "${runtime.lowercase()} trailing Ω",
                ),
                runtimeConsole.getErrorsForExport(),
            )
            CompiledParsingService.invalidateCache(path.toString())
        }
    }

    fun `test Unicode case folding text and editor offsets agree in both compiled runtimes`() = runBlocking {
        Files.writeString(
            grammarPath,
            """
            grammar RuntimeMatrix;
            start: EMOJI word EOF;
            word: UPPER | LOWER;
            EMOJI: '😀';
            UPPER: '𐐀';
            LOWER: '𐐨';
            WS: [ \t\r\n]+ -> skip;
            """
                .trimIndent(),
        )
        val files = LocalFileSystem.getInstance()
        val grammarFile = requireNotNull(files.refreshAndFindFileByNioFile(grammarPath))
        val service = project.getService(PreviewService::class.java)
        service.state.g4pInputFile = requireNotNull(files.refreshAndFindFileByNioFile(fixtureRoot.resolve("input.g4p")))
        service.state.grammarFile = grammarFile
        service.state.liveSettings?.libDir = ""
        val (lexerGrammar, parserGrammar) = requireNotNull(ParsingUtils.loadGrammars(project, grammarFile))
        service.state.lexerGrammar = requireNotNull(lexerGrammar)
        service.state.grammar = requireNotNull(parserGrammar)
        for (runtime in listOf("Java", "Kotlin")) {
            val generated = generatePreviewSources(runtime)
            service.state.liveSettings?.previewRuntime = runtime
            for (strategy in CaseChangingStrategy.values()) {
                service.state.liveSettings?.caseChangingStrategy = strategy
                val parsed =
                    CompiledParsingService.parseWithCompiledClasses(
                        parserGrammar,
                        lexerGrammar,
                        "start",
                        grammarFile,
                        "😀𐐨",
                        generated.toFile(),
                        project,
                    )
                assertTrue(
                    "$runtime/$strategy failed: $parsed",
                    parsed is CompiledParsingService.CompiledParseResult.Success,
                )
                val result = (parsed as CompiledParsingService.CompiledParseResult.Success).result
                assertTrue(
                    "$runtime/$strategy errors: ${result.syntaxErrorListener?.syntaxErrors?.map { it.message }}; runtime errors: ${result.errors}",
                    result.syntaxErrorListener?.syntaxErrors.isNullOrEmpty(),
                )
                val emoji = result.findTerminalAtOffset(0)!!
                assertEquals(emoji, result.findTerminalAtOffset(1))
                val word = result.findTerminalAtOffset(2)!!
                assertEquals(word, result.findTerminalAtOffset(3))
                assertNull(result.findTerminalAtOffset(4))
                assertEquals("😀", result.getTokenFromTerminal(emoji)!!.text)
                val token = result.getTokenFromTerminal(word)!!
                assertEquals("𐐨", token.text)
                assertEquals(2 to 3, token.startIndex to token.stopIndex)
                assertEquals(
                    if (strategy == CaseChangingStrategy.FORCE_UPPERCASE) "UPPER" else "LOWER",
                    result.getSymbolicName(token.type),
                )
                assertEquals(0 to 3, result.getRuleCharacterRange(result.javaTree!!))
                assertEquals(2 to 3, com.antlr4plus.preview.getTreeSelectionOffsets(word))
                assertEquals(word, com.antlr4plus.preview.findTreeNodeAtOffset(result.javaTree!!, 3))
                val hierarchy = com.antlr4plus.preview.hierarchy.wrapHierarchyTree(result.javaTree)!!
                assertEquals(word, com.antlr4plus.preview.findHierarchyNodeAtOffset(hierarchy, 3)!!.userObject)
            }
            CompiledParsingService.invalidateCache(grammarPath.toString())
        }
    }

    fun `test supplementary characters do not become newlines in tokens actions or diagnostics`() = runBlocking {
        val files = LocalFileSystem.getInstance()
        val service = project.getService(PreviewService::class.java)
        service.state.g4pInputFile = requireNotNull(files.refreshAndFindFileByNioFile(fixtureRoot.resolve("input.g4p")))
        service.state.liveSettings?.libDir = ""
        val console = ComposeRuntimeConsolePanel(project)
        service.runtimeConsolePanel = console
        val characters = (1..16).map { String(Character.toChars((it shl 16) + 10)) }
        val input = characters.joinToString("x\r\n") + "x@!"
        val alternatives = characters.joinToString(" | ") { "'$it'" }

        for (runtime in listOf("Java", "Kotlin")) {
            val action =
                if (runtime == "Java") "System.out.println(getLine() + \":\" + getCharPositionInLine());"
                else "println(line.toString() + \":\" + charPositionInLine)"
            val path = fixtureRoot.resolve("LinePositions$runtime.g4")
            Files.writeString(
                path,
                """
                grammar LinePositions$runtime;
                start: (SPECIAL WORD)* EOF;
                SPECIAL: ($alternatives) {$action};
                WORD: 'x';
                BAD: '!';
                WS: [ \t\r\n]+ -> skip;
                """
                    .trimIndent(),
            )
            val grammarFile = requireNotNull(files.refreshAndFindFileByNioFile(path))
            service.state.grammarFile = grammarFile
            service.state.liveSettings?.previewRuntime = runtime
            val (lexerGrammar, parserGrammar) = requireNotNull(ParsingUtils.loadGrammars(project, grammarFile))
            service.state.lexerGrammar = requireNotNull(lexerGrammar)
            service.state.grammar = requireNotNull(parserGrammar)
            val generated = generatePreviewSources(runtime, path)

            // A second parse reuses the compiled classes and DFA caches.
            repeat(2) {
                val parsed =
                    CompiledParsingService.parseWithCompiledClasses(
                        parserGrammar,
                        lexerGrammar,
                        "start",
                        grammarFile,
                        input,
                        generated.toFile(),
                        project,
                    )
                assertTrue("$runtime failed: $parsed", parsed is CompiledParsingService.CompiledParseResult.Success)
                val result = (parsed as CompiledParsingService.CompiledParseResult.Success).result
                assertEquals("$runtime lexer actions", (1..16).map { "$it:1" }, console.getErrorsForExport())
                assertEquals("$runtime displayed token count", 34, result.tokenCount)
                val stats =
                    com.antlr4plus.benchmark.extractProfilerStats(
                        result.kotlinParser ?: result.parser!!,
                        result.isKotlin,
                        com.intellij.openapi.diagnostic.Logger.getInstance(PreviewRuntimeIntegrationTest::class.java),
                    )
                assertEquals("$runtime benchmark token count", 34, stats!!.numTokens)
                for (index in characters.indices) {
                    val special = result.getTokenFromTerminal(result.findTerminalAtOffset(index * 5)!!)!!
                    val word = result.getTokenFromTerminal(result.findTerminalAtOffset(index * 5 + 2)!!)!!
                    assertEquals("$runtime special token", index + 1 to 0, special.line to special.charPositionInLine)
                    assertEquals("$runtime word token", index + 1 to 1, word.line to word.charPositionInLine)
                }
                val errors = result.syntaxErrorListener!!.syntaxErrors
                assertEquals(
                    "$runtime lexer and parser errors",
                    listOf(16 to 2, 16 to 3),
                    errors.map { it.line to it.charPositionInLine },
                )
                val coordinates = InputTextCoordinates(input)
                assertEquals(
                    listOf(input.indexOf('@'), input.indexOf('!')),
                    errors.map { com.antlr4plus.error.syntaxErrorEditorRange(it, coordinates)!!.startOffset },
                )
            }
            CompiledParsingService.invalidateCache(path.toString())
        }
    }

    fun `test Kotlin compatibility preserves initialized state caches and custom simulators`() = runBlocking {
        val files = LocalFileSystem.getInstance()
        val service = project.getService(PreviewService::class.java)
        service.state.g4pInputFile = requireNotNull(files.refreshAndFindFileByNioFile(fixtureRoot.resolve("input.g4p")))
        service.state.liveSettings?.libDir = ""
        service.state.liveSettings?.previewRuntime = "Kotlin"
        val console = ComposeRuntimeConsolePanel(project)
        service.runtimeConsolePanel = console

        for (custom in listOf(false, true)) {
            val name = if (custom) "CustomSimulator" else "InitializedSimulator"
            val simulator =
                if (custom)
                    """
                    object : LexerATNSimulator(this@${name}Lexer, interpreter.atn, interpreter.decisionToDFA, requireNotNull(interpreter.sharedContextCache)) {
                        override fun consume(input: CharStream) {
                            super.consume(input)
                            charPositionInLine += 10
                        }
                    }
                    """
                        .trimIndent()
                else "interpreter"
            val path = fixtureRoot.resolve("$name.g4")
            Files.writeString(
                path,
                """
                grammar $name;
                @lexer::members {
                    val originalSimulator = $simulator
                    init {
                        interpreter = originalSimulator
                        line = 7
                        charPositionInLine = 4
                    }
                }
                start: WORD WORD EOF;
                WORD: 'x' {
                    check(interpreter.atn === originalSimulator.atn)
                    check(interpreter.decisionToDFA === originalSimulator.decisionToDFA)
                    check(interpreter.sharedContextCache === originalSimulator.sharedContextCache)
                    check((interpreter === originalSimulator) == $custom)
                    println(line.toString() + ":" + charPositionInLine)
                };
                """
                    .trimIndent(),
            )
            val grammarFile = requireNotNull(files.refreshAndFindFileByNioFile(path))
            service.state.grammarFile = grammarFile
            val (lexerGrammar, parserGrammar) = requireNotNull(ParsingUtils.loadGrammars(project, grammarFile))
            service.state.lexerGrammar = requireNotNull(lexerGrammar)
            service.state.grammar = requireNotNull(parserGrammar)
            val generated = generatePreviewSources("Kotlin", path)

            repeat(2) {
                val parsed =
                    CompiledParsingService.parseWithCompiledClasses(
                        parserGrammar,
                        lexerGrammar,
                        "start",
                        grammarFile,
                        "xx",
                        generated.toFile(),
                        project,
                    )
                assertTrue("$name failed: $parsed", parsed is CompiledParsingService.CompiledParseResult.Success)
                val result = (parsed as CompiledParsingService.CompiledParseResult.Success).result
                assertTrue(result.syntaxErrorListener!!.syntaxErrors.isEmpty())
                val first = result.getTokenFromTerminal(result.findTerminalAtOffset(0)!!)!!
                val second = result.getTokenFromTerminal(result.findTerminalAtOffset(1)!!)!!
                assertEquals(7 to 4, first.line to first.charPositionInLine)
                assertEquals(7 to if (custom) 15 else 5, second.line to second.charPositionInLine)
                assertEquals(if (custom) listOf("7:15", "7:26") else listOf("7:5", "7:6"), console.getErrorsForExport())
                val parser = result.kotlinParser!!
                val tokens = parser.javaClass.getMethod("getTokenStream").invoke(parser)
                val lexer = tokens.javaClass.getMethod("getTokenSource").invoke(tokens)
                val interpreter = lexer.javaClass.getMethod("getInterpreter").invoke(lexer)
                // Installing again must not reset an active lexer or discard a custom simulator.
                lexer.javaClass.classLoader
                    .loadClass("com.antlr4plus.parsing.kotlin.KotlinLexerCompatibility")
                    .getMethod("install", Any::class.java)
                    .invoke(null, lexer)
                assertSame(interpreter, lexer.javaClass.getMethod("getInterpreter").invoke(lexer))
            }
            CompiledParsingService.invalidateCache(path.toString())
        }
    }

    fun `test Kotlin benchmarks share preview Unicode positions folding and original token text`() = runBlocking {
        val special = String(Character.toChars(0x1000A))
        val input = "${special}𐐨"
        Files.writeString(
            grammarPath,
            """
            grammar RuntimeMatrix;
            start: SPECIAL UPPER EOF {
                check(numberOfSyntaxErrors == 0)
                check(tokenStream.get(0).text == "$special")
                check(tokenStream.get(1).text == "𐐨")
                check(tokenStream.get(1).line == 1)
                check(tokenStream.get(1).charPositionInLine == 1)
            };
            SPECIAL: '$special';
            UPPER: '𐐀';
            """
                .trimIndent(),
        )
        Files.writeString(fixtureRoot.resolve("input.g4p"), input)
        val files = LocalFileSystem.getInstance()
        val grammarFile = requireNotNull(files.refreshAndFindFileByNioFile(grammarPath))
        val inputFile = requireNotNull(files.refreshAndFindFileByNioFile(fixtureRoot.resolve("input.g4p")))
        com.intellij.testFramework.PsiTestUtil.addContentRoot(module, inputFile.parent)
        val service = project.getService(PreviewService::class.java)
        service.state.g4pInputFile = inputFile
        service.state.grammarFile = grammarFile
        service.state.liveSettings?.libDir = ""
        service.state.liveSettings?.previewRuntime = "Kotlin"
        service.state.liveSettings?.caseChangingStrategy = CaseChangingStrategy.FORCE_UPPERCASE
        val (lexerGrammar, parserGrammar) = requireNotNull(ParsingUtils.loadGrammars(project, grammarFile))
        service.state.lexerGrammar = requireNotNull(lexerGrammar)
        service.state.grammar = requireNotNull(parserGrammar)
        val outputRoot = fixtureRoot.resolve("benchmark")
        val generated = generatePreviewSources("Kotlin", outputDirectory = outputRoot.resolve("preview/kotlin"))
        val preview =
            CompiledParsingService.parseWithCompiledClasses(
                parserGrammar,
                lexerGrammar,
                "start",
                grammarFile,
                input,
                generated.toFile(),
                project,
            )
        assertTrue("Preview failed: $preview", preview is CompiledParsingService.CompiledParseResult.Success)
        assertEquals(3, (preview as CompiledParsingService.CompiledParseResult.Success).result.tokenCount)

        val benchmark =
            com.antlr4plus.benchmark
                .BenchmarkRunner()
                .runParsingBenchmark(
                    com.antlr4plus.benchmark.BenchmarkRunner.BenchmarkInput(
                        grammar = parserGrammar,
                        lexerGrammar = lexerGrammar,
                        grammarFile = grammarFile,
                        g4pFile = inputFile,
                        inputText = input,
                        startRuleName = "start",
                        previewRuntime = "Kotlin",
                        configuredOutputDir = outputRoot.toString(),
                        packageName = null,
                        caseChangingStrategy = CaseChangingStrategy.FORCE_UPPERCASE,
                    ),
                    project,
                    com.antlr4plus.benchmark.BenchmarkRunner.BenchmarkConfig(
                        warmupDurationMs = 1,
                        measurementDurationMs = 200,
                        singleParseTimeoutMs = 2000,
                        parsingTimeoutMs = 5000,
                    ),
                )
        assertNotNull("Benchmark must execute the same successful grammar actions as the preview", benchmark)
        assertTrue(benchmark!!.iterations > 0)
        assertEquals(3, benchmark.numTokens)
        assertEquals(input.length, benchmark.inputChars)
        assertEquals(1, benchmark.inputLines)
    }

    fun testJavaProfilerHandlesDeepGeneratedLeftRecursiveTrees() = checkDeepProfilerTree("Java")

    fun testKotlinProfilerHandlesDeepGeneratedLeftRecursiveTrees() = checkDeepProfilerTree("Kotlin")

    private fun checkDeepProfilerTree(runtime: String) = runBlocking {
        Files.writeString(
            grammarPath,
            "grammar RuntimeMatrix; start: expression EOF; expression: expression '+' WORD | WORD; WORD: 'x';",
        )
        val files = LocalFileSystem.getInstance()
        val grammarFile = requireNotNull(files.refreshAndFindFileByNioFile(grammarPath))
        val inputFile = requireNotNull(files.refreshAndFindFileByNioFile(fixtureRoot.resolve("input.g4p")))
        val service = project.getService(PreviewService::class.java)
        service.state.g4pInputFile = inputFile
        service.state.grammarFile = grammarFile
        requireNotNull(service.state.liveSettings).apply {
            libDir = ""
            previewRuntime = runtime
            parsingTimeoutMs = 15_000
        }
        val (lexerGrammar, parserGrammar) = requireNotNull(ParsingUtils.loadGrammars(project, grammarFile))
        service.state.lexerGrammar = requireNotNull(lexerGrammar)
        service.state.grammar = requireNotNull(parserGrammar)
        val generated = generatePreviewSources(runtime)
        val terms = 10_000
        val input = List(terms) { "x" }.joinToString("+")
        val parsed =
            CompiledParsingService.parseWithCompiledClasses(
                parserGrammar,
                lexerGrammar,
                "start",
                grammarFile,
                input,
                generated.toFile(),
                project,
            )
        assertTrue("$runtime deep parse failed: $parsed", parsed is CompiledParsingService.CompiledParseResult.Success)
        val result = (parsed as CompiledParsingService.CompiledParseResult.Success).result
        assertTrue(result.syntaxErrorListener!!.syntaxErrors.isEmpty())
        val tree = requireNotNull(result.javaTree)
        var firstBranch: org.antlr.v4.runtime.tree.Tree? = tree
        var depth = 0
        while (firstBranch != null && firstBranch.childCount > 0) {
            depth++
            firstBranch = firstBranch.getChild(0)
        }
        assertTrue("Generated parse tree must actually be deep: $depth", depth >= terms)
        val model =
            if (runtime == "Java") {
                val parser = requireNotNull(result.parser)
                val javaTree = tree as org.antlr.v4.runtime.tree.ParseTree
                val matches =
                    com.antlr4plus.statistics.collectMatchingRuleContexts(
                        javaTree,
                        parser.ruleNames.indexOf("expression"),
                    )
                assertEquals(terms, matches.size)
                assertEquals(input.length - 1, matches.first().stop.stopIndex)
                assertEquals(0, matches.last().stop.stopIndex)
                val counts = com.antlr4plus.statistics.countDecisionInvocationsFromParseTree(parser, javaTree)
                com.antlr4plus.statistics.SimpleProfilerTableDataModel(
                    requireNotNull(parser.parseInfo),
                    parser,
                    ruleExecutions = counts,
                )
            } else {
                requireNotNull(com.antlr4plus.statistics.KotlinProfilerTableDataModel.create(result, javaTree = tree))
            }
        assertTrue(model.rowCount > 0)
        for (row in 0 until model.rowCount) assertEquals(terms.toLong(), model.getValueAt(row, 7))
    }

    fun testJavaBenchmarkUsesUnsavedInputBeforeLaterEdits() = checkBenchmarkInputSnapshot("Java", "unsaved")

    fun testKotlinBenchmarkUsesUnsavedInputBeforeLaterEdits() = checkBenchmarkInputSnapshot("Kotlin", "unsaved")

    fun testJavaBenchmarkKeepsItsInputAfterTheFileIsSavedAgain() = checkBenchmarkInputSnapshot("Java", "saved")

    fun testKotlinBenchmarkKeepsItsInputAfterTheFileIsSavedAgain() = checkBenchmarkInputSnapshot("Kotlin", "saved")

    fun testJavaBenchmarkUsesTheEditorCharsetAndLineEndings() = checkBenchmarkInputSnapshot("Java", "encoded")

    fun testKotlinBenchmarkUsesTheEditorCharsetAndLineEndings() = checkBenchmarkInputSnapshot("Kotlin", "encoded")

    fun testJavaBenchmarkKeepsAnEmptyInputSnapshot() = checkBenchmarkInputSnapshot("Java", "empty")

    fun testKotlinBenchmarkKeepsAnEmptyInputSnapshot() = checkBenchmarkInputSnapshot("Kotlin", "empty")

    private fun checkBenchmarkInputSnapshot(runtime: String, scenario: String) = runBlocking {
        val expected =
            when (scenario) {
                "encoded" -> "café\nnaïve"
                "empty" -> ""
                else -> "alpha beta\nomega"
            }
        val encoded = scenario == "encoded"
        val charset = if (encoded) Charsets.ISO_8859_1 else Charsets.UTF_8
        val saved =
            when (scenario) {
                "encoded" -> "café\r\nnaïve"
                "saved" -> expected
                else -> "disk"
            }
        Files.write(fixtureRoot.resolve("input.g4p"), saved.toByteArray(charset))
        val literal = expected.replace("\n", "\\n")
        val assertion =
            if (runtime == "Java") {
                "if (!\"$literal\".equals(getInputStream().getTokenSource().getInputStream().toString())) " +
                    "throw new IllegalStateException(\"Unexpected benchmark input\");"
            } else {
                "check(tokenStream.tokenSource.inputStream.toString() == \"$literal\")"
            }
        Files.writeString(
            grammarPath,
            """
            grammar RuntimeMatrix;
            start: WORD* EOF { $assertion };
            WORD: [a-zA-Z\u0080-\uFFFF]+;
            WS: [ \t\r\n]+ -> skip;
            """
                .trimIndent(),
        )
        val files = LocalFileSystem.getInstance()
        val grammarFile = requireNotNull(files.refreshAndFindFileByNioFile(grammarPath))
        val inputFile = requireNotNull(files.refreshAndFindFileByNioFile(fixtureRoot.resolve("input.g4p")))
        com.intellij.openapi.application.runWriteAction { inputFile.charset = charset }
        com.intellij.testFramework.PsiTestUtil.addContentRoot(module, inputFile.parent)
        val documents = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance()
        val document = requireNotNull(documents.getDocument(inputFile))
        if (scenario == "unsaved" || scenario == "empty") {
            com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
                document.setText(expected)
            }
            assertTrue(documents.isDocumentUnsaved(document))
            assertEquals("disk", String(inputFile.contentsToByteArray(), charset))
        }
        assertEquals("The editor must see the expected input before capture", expected, document.text)

        val service = project.getService(PreviewService::class.java)
        val state = service.state
        state.g4pInputFile = inputFile
        state.grammarFile = grammarFile
        state.startRuleName = "start"
        val outputRoot = fixtureRoot.resolve("benchmark")
        requireNotNull(state.liveSettings).apply {
            libDir = ""
            previewRuntime = runtime
            outputDir = outputRoot.toString()
        }
        val (lexerGrammar, parserGrammar) = requireNotNull(ParsingUtils.loadGrammars(project, grammarFile))
        state.lexerGrammar = requireNotNull(lexerGrammar)
        state.grammar = requireNotNull(parserGrammar)
        val antlrConsole = com.antlr4plus.error.ComposeAntlrErrorConsolePanel(project)
        service.antlrConsolePanel = antlrConsole
        val generated =
            generatePreviewSources(runtime, outputDirectory = outputRoot.resolve("preview/${runtime.lowercase()}"))
        val preview =
            CompiledParsingService.parseWithCompiledClasses(
                parserGrammar,
                lexerGrammar,
                "start",
                grammarFile,
                document.text,
                generated.toFile(),
                project,
            )
        assertTrue(
            "$runtime/$scenario preview failed: $preview, ${antlrConsole.getErrorsForExport()}",
            preview is CompiledParsingService.CompiledParseResult.Success,
        )
        val previewResult = (preview as CompiledParsingService.CompiledParseResult.Success).result
        assertTrue(previewResult.syntaxErrorListener!!.syntaxErrors.isEmpty())
        val snapshot = requireNotNull(com.antlr4plus.benchmark.BenchmarkRunner.captureInput(state))
        if (!encoded) {
            com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
                document.setText("later input")
            }
            if (scenario == "saved") {
                documents.saveDocument(document)
                assertEquals("later input", String(inputFile.contentsToByteArray(), charset))
            } else {
                assertTrue(documents.isDocumentUnsaved(document))
            }
        }

        val benchmark =
            requireNotNull(
                com.antlr4plus.benchmark
                    .BenchmarkRunner()
                    .runParsingBenchmark(
                        snapshot,
                        project,
                        com.antlr4plus.benchmark.BenchmarkRunner.BenchmarkConfig(
                            warmupDurationMs = 1,
                            measurementDurationMs = 200,
                            singleParseTimeoutMs = 2000,
                            parsingTimeoutMs = 5000,
                        ),
                    ),
            )
        assertEquals("$runtime/$scenario captured input length", expected.length, benchmark.inputChars)
        assertEquals("$runtime/$scenario captured line count", expected.count { it == '\n' } + 1, benchmark.inputLines)
        assertTrue("$runtime/$scenario must parse the captured text successfully", benchmark.iterations > 0)
        assertEquals(previewResult.tokenCount, benchmark.numTokens)
    }

    fun testCancellationFromGeneratedConstructorsAndActionsEscapesPreviewsAndBenchmarks() = runBlocking {
        Files.writeString(fixtureRoot.resolve("input.g4p"), "cancel")
        val files = LocalFileSystem.getInstance()
        val inputFile = requireNotNull(files.refreshAndFindFileByNioFile(fixtureRoot.resolve("input.g4p")))
        com.intellij.testFramework.PsiTestUtil.addContentRoot(module, inputFile.parent)
        val service = project.getService(PreviewService::class.java)
        service.state.g4pInputFile = inputFile
        service.state.liveSettings?.libDir = ""
        val console = ComposeRuntimeConsolePanel(project)
        service.runtimeConsolePanel = console
        val antlrConsole = com.antlr4plus.error.ComposeAntlrErrorConsolePanel(project)
        service.antlrConsolePanel = antlrConsole

        for (runtime in listOf("Java", "Kotlin")) {
            for (location in listOf("LexerConstructor", "ParserConstructor", "LexerAction", "ParserAction")) {
                val name = "Cancel$runtime$location"
                val expectedMessage = "cancel $name"
                val cancellation =
                    if (runtime == "Java") "throw new java.util.concurrent.CancellationException(\"$expectedMessage\");"
                    else "throw java.util.concurrent.CancellationException(\"$expectedMessage\")"
                val condition =
                    when (location) {
                        "LexerConstructor" ->
                            if (runtime == "Java") "getInputStream().LA(1) == 99" else "inputStream?.LA(1) == 99"
                        "ParserConstructor" ->
                            if (runtime == "Java") "\"cancel\".equals(getCurrentToken().getText())"
                            else "currentToken?.text == \"cancel\""
                        else -> {
                            val text =
                                if (location == "LexerAction") {
                                    if (runtime == "Java") "getText()" else "text"
                                } else "${'$'}value.text"
                            if (runtime == "Java") "\"cancel\".equals($text)" else "$text == \"cancel\""
                        }
                    }
                val action = "if ($condition) { $cancellation }"
                val members =
                    if (location.endsWith("Constructor")) {
                        val owner = if (location.startsWith("Lexer")) "lexer" else "parser"
                        "@$owner::members { ${if (runtime == "Kotlin") "init" else ""} { $action } }"
                    } else ""
                val parserAction = if (location == "ParserAction") "{$action}" else ""
                val lexerAction = if (location == "LexerAction") "{$action}" else ""
                val path = fixtureRoot.resolve("$name.g4")
                Files.writeString(
                    path,
                    "grammar $name;\n$members\nstart: value=WORD $parserAction EOF;\nWORD: [a-z]+ $lexerAction;",
                )
                val grammarFile = requireNotNull(files.refreshAndFindFileByNioFile(path))
                service.state.grammarFile = grammarFile
                service.state.liveSettings?.previewRuntime = runtime
                val (lexerGrammar, parserGrammar) =
                    requireNotNull(ParsingUtils.loadGrammars(project, grammarFile)) {
                        "$name grammar loading failed: ${antlrConsole.getErrorsForExport()}"
                    }
                service.state.lexerGrammar = requireNotNull(lexerGrammar)
                service.state.grammar = requireNotNull(parserGrammar)
                val outputRoot = fixtureRoot.resolve(name)
                val generated =
                    generatePreviewSources(
                        runtime,
                        path,
                        outputDirectory = outputRoot.resolve("preview").resolve(runtime.lowercase()),
                    )
                suspend fun parse(text: String) =
                    CompiledParsingService.parseWithCompiledClasses(
                        parserGrammar,
                        lexerGrammar,
                        "start",
                        grammarFile,
                        text,
                        generated.toFile(),
                        project,
                    )
                try {
                    val warmup = parse("ok")
                    assertTrue(
                        "$name warmup failed: $warmup, ${antlrConsole.getErrorsForExport()}",
                        warmup is CompiledParsingService.CompiledParseResult.Success,
                    )
                    val previewFailure =
                        try {
                            parse("cancel")
                            null
                        } catch (failure: Throwable) {
                            failure
                        }
                    assertTrue(
                        "$name preview swallowed cancellation: $previewFailure",
                        previewFailure is java.util.concurrent.CancellationException,
                    )
                    assertEquals(expectedMessage, previewFailure!!.message)
                    assertTrue(
                        "Cancellation must not be reported as a grammar error",
                        console.getErrorsForExport().isNullOrEmpty(),
                    )
                    assertFalse(CompiledParsingService.hasGrammarMutexForTest(path.toString()))
                    assertTrue(
                        "$name did not recover",
                        parse("ok") is CompiledParsingService.CompiledParseResult.Success,
                    )

                    val benchmarkFailure =
                        try {
                            com.antlr4plus.benchmark
                                .BenchmarkRunner()
                                .runParsingBenchmark(
                                    com.antlr4plus.benchmark.BenchmarkRunner.BenchmarkInput(
                                        grammar = parserGrammar,
                                        lexerGrammar = lexerGrammar,
                                        grammarFile = grammarFile,
                                        g4pFile = inputFile,
                                        inputText = "cancel",
                                        startRuleName = "start",
                                        previewRuntime = runtime,
                                        configuredOutputDir = outputRoot.toString(),
                                        packageName = null,
                                        caseChangingStrategy = CaseChangingStrategy.LEAVE_AS_IS,
                                    ),
                                    project,
                                    com.antlr4plus.benchmark.BenchmarkRunner.BenchmarkConfig(
                                        warmupDurationMs = 1,
                                        measurementDurationMs = 1,
                                    ),
                                )
                            null
                        } catch (failure: Throwable) {
                            failure
                        }
                    assertTrue(
                        "$name benchmark swallowed cancellation: $benchmarkFailure",
                        benchmarkFailure is java.util.concurrent.CancellationException,
                    )
                    assertEquals(expectedMessage, benchmarkFailure!!.message)
                } finally {
                    CompiledParsingService.invalidateCache(path.toString())
                }
            }
        }
    }

    private fun parserBinaryName(result: CompiledParsingService.CompiledParseResult): String {
        val parsingResult = (result as CompiledParsingService.CompiledParseResult.Success).result
        return parsingResult.kotlinParser?.javaClass?.name ?: requireNotNull(parsingResult.parser).javaClass.name
    }

    private fun generatePreviewSources(
        runtime: String,
        inputGrammar: Path = grammarPath,
        packageName: String? = null,
        outputDirectory: Path? = null,
    ): Path {
        val grammarName = inputGrammar.fileName.toString().substringBeforeLast('.')
        val output = outputDirectory ?: fixtureRoot.resolve("preview").resolve(runtime.lowercase()).resolve(grammarName)
        Files.createDirectories(output)
        val arguments =
            mutableListOf(
                "-Dlanguage=$runtime",
                "-o",
                output.toString(),
                "-lib",
                fixtureRoot.toString(),
            )
        if (!packageName.isNullOrBlank()) {
            arguments.add("-package")
            arguments.add(packageName)
        }
        arguments.add(inputGrammar.toString())
        val tool = Tool(arguments.toTypedArray())
        val listener = ANTLRToolListenerImpl(tool, null)
        tool.removeListeners()
        tool.addListener(listener)

        val thrown = runCatching { tool.processGrammarsOnCommandLine() }.exceptionOrNull()

        assertNull("$runtime generation threw $thrown", thrown)
        assertTrue(
            "$runtime generation errors: ${listener.errors}",
            listener.errors.isEmpty() && tool.numErrors == 0,
        )
        val extension = if (runtime == "Kotlin") "kt" else "java"
        assertTrue(
            "$runtime generated no .$extension files",
            output.toFile().walkTopDown().any { it.extension == extension },
        )
        return output
    }
}
