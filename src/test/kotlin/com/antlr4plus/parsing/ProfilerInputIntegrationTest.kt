package com.antlr4plus.parsing

import com.antlr4plus.benchmark.BenchmarkRunner
import com.antlr4plus.previewService.PreviewService
import com.antlr4plus.state.PreviewState
import com.antlr4plus.statistics.ComposeProfilerPanel
import com.antlr4plus.timeline.TokenTimelineBuilder
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.antlr.v4.Tool

/** Uses actual parsed/lexed input and asynchronous profiler publication, including intervening editor changes. */
class ProfilerInputIntegrationTest : BasePlatformTestCase() {
    private lateinit var root: Path

    override fun setUp() {
        super.setUp()
        root = Files.createTempDirectory("antlr4plus-profiler-input-")
    }

    override fun tearDown() {
        try {
            if (::root.isInitialized) {
                CompiledParsingService.invalidateCache(root.resolve("MetricsProbe.g4").toString())
                root.toFile().deleteRecursively()
            }
        } finally {
            super.tearDown()
        }
    }

    fun testJavaMetricsUseUnsavedUnicodeInput() = checkMetrics("Java", "unsaved")

    fun testKotlinMetricsUseUnsavedUnicodeInput() = checkMetrics("Kotlin", "unsaved")

    fun testLexerMetricsUseUnsavedUnicodeInput() = checkMetrics("Lexer", "unsaved")

    fun testJavaMetricsUseDecodedAndNormalizedInput() = checkMetrics("Java", "encoded")

    fun testKotlinMetricsUseDecodedAndNormalizedInput() = checkMetrics("Kotlin", "encoded")

    fun testLexerMetricsUseDecodedAndNormalizedInput() = checkMetrics("Lexer", "encoded")

    fun testJavaMetricsKeepParsedInputAfterAnotherSave() = checkMetrics("Java", "saved")

    fun testKotlinMetricsKeepParsedInputAfterAnotherSave() = checkMetrics("Kotlin", "saved")

    fun testLexerMetricsKeepLexedInputAfterAnotherSave() = checkMetrics("Lexer", "saved")

    fun testJavaMetricsCountEmptyInputConsistently() = checkMetrics("Java", "empty")

    fun testKotlinMetricsCountEmptyInputConsistently() = checkMetrics("Kotlin", "empty")

    fun testLexerMetricsCountEmptyInputConsistently() = checkMetrics("Lexer", "empty")

    private fun checkMetrics(runtime: String, scenario: String) = runBlocking {
        val fixture = prepare(runtime, scenario)
        if (scenario == "saved") replaceInput(fixture, "later saved text", save = true)
        val panel = ComposeProfilerPanel(project, testRootDisposable)
        try {
            if (runtime == "Lexer") {
                panel.setLexerOnlyData(fixture.state, 1_234_000, fixture.tokenCount, fixture.text)
                panel.runBenchmark()
                assertFalse(readPanel(panel, "isBenchmarkRunning") as Boolean)
                assertFalse(readPanel(panel, "getBenchmarkFailed") as Boolean)
                assertNull(readPanel(panel, "getBenchmarkResult"))
            } else {
                panel.setProfilerData(fixture.state, 1_234_000, fixture.text)
                waitForBenchmark(panel)
            }
            val stats = readPanel(panel, "getInitialStats") as ComposeProfilerPanel.InitialStats
            assertEquals(
                "$runtime/$scenario must describe the processed snapshot",
                fixture.text.length,
                stats.inputChars,
            )
            assertEquals("$runtime/$scenario line count", fixture.text.count { it == '\n' } + 1, stats.inputLines)
            assertEquals(fixture.tokenCount, stats.numTokens)
            assertEquals(1.234, stats.parseTimeMs)
            if (runtime != "Lexer") {
                assertFalse(readPanel(panel, "getBenchmarkFailed") as Boolean)
                assertEquals(0, (readPanel(panel, "getBenchmarkResult") as BenchmarkRunner.BenchmarkResult).iterations)
            }
        } finally {
            panel.dispose()
        }
    }

    fun testJavaProfilerBenchmarksTheDisplayedSnapshotAfterLaterEdits() = checkBenchmarkSnapshot("Java")

    fun testKotlinProfilerBenchmarksTheDisplayedSnapshotAfterLaterEdits() = checkBenchmarkSnapshot("Kotlin")

    private fun checkBenchmarkSnapshot(runtime: String) = runBlocking {
        val fixture = prepare(runtime, "saved")
        replaceInput(fixture, "different saved input\nwith more tokens", save = true)
        requireNotNull(fixture.state.liveSettings).benchmarkMeasurementDurationMs = 50
        val panel = ComposeProfilerPanel(project, testRootDisposable)
        try {
            panel.setProfilerData(fixture.state, 1_234_000, fixture.text)
            assertBenchmarkMatches(panel, fixture)
            replaceInput(fixture, "another unsaved edit")
            panel.runBenchmark()
            assertBenchmarkMatches(panel, fixture)
            panel.resetData()
            panel.runBenchmark()
            assertNull(readPanel(panel, "getInitialStats"))
            assertNull(readPanel(panel, "getBenchmarkResult"))
            assertFalse(readPanel(panel, "isBenchmarkRunning") as Boolean)
        } finally {
            panel.dispose()
        }
    }

    private fun assertBenchmarkMatches(panel: ComposeProfilerPanel, fixture: Fixture) {
        waitForBenchmark(panel)
        // The generated grammar also asserts the entire input string on every parse.
        assertFalse(
            "Benchmark must parse the displayed preview's input",
            readPanel(panel, "getBenchmarkFailed") as Boolean,
        )
        val result = readPanel(panel, "getBenchmarkResult") as BenchmarkRunner.BenchmarkResult
        assertTrue(result.iterations > 0)
        assertEquals(fixture.text.length, result.inputChars)
        assertEquals(fixture.text.count { it == '\n' } + 1, result.inputLines)
        assertEquals(fixture.tokenCount, result.numTokens)
        val stats = readPanel(panel, "getInitialStats") as ComposeProfilerPanel.InitialStats
        assertEquals(result.inputChars, stats.inputChars)
        assertEquals(result.inputLines, stats.inputLines)
        assertEquals(result.meanMs, stats.parseTimeMs)
    }

    private fun waitForBenchmark(panel: ComposeProfilerPanel) {
        PlatformTestUtil.waitWithEventsDispatching(
            "Benchmark did not finish",
            { !(readPanel(panel, "isBenchmarkRunning") as Boolean) },
            30,
        )
    }

    private fun readPanel(panel: ComposeProfilerPanel, getter: String): Any? =
        panel.javaClass.getDeclaredMethod(getter).apply { isAccessible = true }.invoke(panel)

    private data class Fixture(val state: PreviewState, val text: String, val tokenCount: Int)

    private fun replaceInput(fixture: Fixture, text: String, save: Boolean = false) {
        val files = FileDocumentManager.getInstance()
        val document = requireNotNull(files.getDocument(requireNotNull(fixture.state.g4pInputFile)))
        WriteCommandAction.runWriteCommandAction(project) { document.setText(text) }
        if (save) files.saveDocument(document) else assertTrue(files.isDocumentUnsaved(document))
    }

    private suspend fun prepare(runtime: String, scenario: String): Fixture {
        val expected =
            when (scenario) {
                "unsaved" -> "alpha 😀𐐀\nomega\n"
                "encoded" -> "café\nnaïve\n"
                "empty" -> ""
                else -> "alpha\nbeta"
            }
        val charset = if (scenario == "encoded") Charsets.ISO_8859_1 else Charsets.UTF_8
        val saved =
            when (scenario) {
                "unsaved" -> "disk"
                "encoded" -> expected.replace("\n", "\r\n")
                else -> expected
            }
        val literal = expected.replace("\n", "\\n")
        val assertion =
            if (runtime == "Java") {
                "if (!\"$literal\".equals(getInputStream().getTokenSource().getInputStream().toString())) " +
                    "throw new IllegalStateException(\"Profiler benchmark input changed\");"
            } else {
                "check(tokenStream.tokenSource.inputStream.toString() == \"$literal\")"
            }
        val grammarPath = root.resolve("MetricsProbe.g4")
        val header =
            if (runtime == "Lexer") "lexer grammar MetricsProbe;"
            else "grammar MetricsProbe;\nstart: (WORD | SYMBOL)* EOF { $assertion };"
        Files.writeString(
            grammarPath,
            "$header\nWORD: [a-zA-Z\\u0080-\\uFFFF]+;\nWS: [ \\t\\r\\n]+ -> skip;\nSYMBOL: .;",
        )
        Files.write(root.resolve("input.g4p"), saved.toByteArray(charset))
        val files = LocalFileSystem.getInstance()
        val grammarFile = requireNotNull(files.refreshAndFindFileByNioFile(grammarPath))
        val inputFile = requireNotNull(files.refreshAndFindFileByNioFile(root.resolve("input.g4p")))
        runWriteAction { inputFile.charset = charset }
        PsiTestUtil.addContentRoot(module, inputFile.parent)
        val documents = FileDocumentManager.getInstance()
        val document = requireNotNull(documents.getDocument(inputFile))
        if (scenario == "unsaved") {
            WriteCommandAction.runWriteCommandAction(project) { document.setText(expected) }
            assertTrue(documents.isDocumentUnsaved(document))
            assertEquals("disk", String(inputFile.contentsToByteArray(), charset))
        }
        val text = readInputText(inputFile)
        assertEquals(expected, text)
        val service = project.getService(PreviewService::class.java)
        val state = service.state
        state.g4pInputFile = inputFile
        state.grammarFile = grammarFile
        state.startRuleName = if (runtime == "Lexer") null else "start"
        val outputRoot = root.resolve("generated")
        requireNotNull(state.liveSettings).apply {
            libDir = ""
            previewRuntime = if (runtime == "Lexer") "Java" else runtime
            outputDir = outputRoot.toString()
            benchmarkWarmupDurationMs = 0
            benchmarkMeasurementDurationMs = 0
            parsingTimeoutMs = 10_000
        }
        val console = com.antlr4plus.error.ComposeAntlrErrorConsolePanel(project)
        service.antlrConsolePanel = console
        val (lexerGrammar, parserGrammar) =
            requireNotNull(ParsingUtils.loadGrammars(project, grammarFile)) {
                console.getErrorsForExport().toString()
            }
        state.lexerGrammar = requireNotNull(lexerGrammar)
        state.grammar = parserGrammar
        if (runtime == "Lexer") {
            assertNull(parserGrammar)
            val timeline = TokenTimelineBuilder.buildTimeline(lexerGrammar, text)
            state.tokenTimeline = timeline
            return Fixture(state, text, timeline.tokens.size)
        }
        val generated = outputRoot.resolve("preview/${runtime.lowercase()}")
        Files.createDirectories(generated)
        val tool = Tool(arrayOf("-Dlanguage=$runtime", "-o", generated.toString(), grammarPath.toString()))
        val listener = ANTLRToolListenerImpl(tool, null)
        tool.removeListeners()
        tool.addListener(listener)
        tool.processGrammarsOnCommandLine()
        assertTrue("Generation failed: ${listener.errors}", listener.errors.isEmpty() && tool.numErrors == 0)
        val parsed =
            CompiledParsingService.parseWithCompiledClasses(
                requireNotNull(parserGrammar),
                lexerGrammar,
                "start",
                grammarFile,
                text,
                generated.toFile(),
                project,
            )
        assertTrue(
            "Preview failed: $parsed ${console.getErrorsForExport()}",
            parsed is CompiledParsingService.CompiledParseResult.Success,
        )
        val result = (parsed as CompiledParsingService.CompiledParseResult.Success).result
        assertTrue(result.syntaxErrorListener!!.syntaxErrors.isEmpty())
        state.parsingResult = result
        return Fixture(state, text, result.tokenCount)
    }
}
