package com.antlr4plus.parsing

import com.antlr4plus.benchmark.BenchmarkRunner
import com.antlr4plus.previewService.PreviewService
import com.antlr4plus.state.PreviewState
import com.antlr4plus.statistics.ComposeProfilerPanel
import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.antlr.v4.Tool

class BenchmarkFailureIntegrationTest : BasePlatformTestCase() {
    private lateinit var root: Path

    override fun setUp() {
        super.setUp()
        root = Files.createTempDirectory("antlr4plus-benchmark-failure-")
    }

    override fun tearDown() {
        try {
            if (::root.isInitialized) {
                CompiledParsingService.invalidateCache(root.resolve("FailureProbe.g4").toString())
                root.toFile().deleteRecursively()
            }
        } finally {
            super.tearDown()
        }
    }

    fun testJavaLexerConstructorFailureAbortsEveryBenchmarkPhase() = checkFailure("Java", "LexerConstructor")

    fun testKotlinLexerConstructorFailureAbortsEveryBenchmarkPhase() = checkFailure("Kotlin", "LexerConstructor")

    fun testJavaParserConstructorFailureAbortsEveryBenchmarkPhase() = checkFailure("Java", "ParserConstructor")

    fun testKotlinParserConstructorFailureAbortsEveryBenchmarkPhase() = checkFailure("Kotlin", "ParserConstructor")

    fun testJavaLexerActionFailureAbortsEveryBenchmarkPhase() = checkFailure("Java", "LexerAction")

    fun testKotlinLexerActionFailureAbortsEveryBenchmarkPhase() = checkFailure("Kotlin", "LexerAction")

    fun testJavaParserActionFailureAbortsEveryBenchmarkPhase() = checkFailure("Java", "ParserAction")

    fun testKotlinParserActionFailureAbortsEveryBenchmarkPhase() = checkFailure("Kotlin", "ParserAction")

    private fun checkFailure(runtime: String, location: String) = runBlocking {
        val fixture = prepare(runtime, location)
        val runner = BenchmarkRunner()
        for ((offset, phase) in listOf("warmup", "measurement", "final profile").withIndex()) {
            val failAt = offset + 1
            fixture.reset(failAt)
            val result = runner.runParsingBenchmark(fixture.input, project, oneMeasurement())
            assertNull("$runtime/$location $phase failure must abort, received $result", result)
            assertEquals("A failed parse must not be retried or followed by another phase", failAt, fixture.calls())
        }
        fixture.reset(0)
        val recovered = requireNotNull(runner.runParsingBenchmark(fixture.input, project, oneMeasurement()))
        assertEquals(1, recovered.iterations)
        assertEquals(2, recovered.numTokens)
    }

    fun testJavaBenchmarkConstructsOnlyParsersThatAreActuallyParsed() = checkConstructionCount("Java")

    fun testKotlinBenchmarkConstructsOnlyParsersThatAreActuallyParsed() = checkConstructionCount("Kotlin")

    private fun checkConstructionCount(runtime: String) = runBlocking {
        val fixture = prepare(runtime, "ParserConstructor")
        fixture.reset(0)
        val result = requireNotNull(BenchmarkRunner().runParsingBenchmark(fixture.input, project, oneMeasurement()))
        assertEquals(1, result.iterations)
        assertEquals("Only warmup, measured parse and final profile may construct a parser", 3, fixture.calls())
        assertEquals(2, result.numTokens)
        fixture.reset(0)
        val empty =
            requireNotNull(
                BenchmarkRunner()
                    .runParsingBenchmark(fixture.input, project, oneMeasurement().copy(measurementDurationMs = 0)),
            )
        assertEquals(0, empty.iterations)
        assertTrue(empty.allTimesNs.isEmpty())
        assertEquals("An empty measurement window runs only the required warmup", 1, fixture.calls())
    }

    fun testJavaProfilerKeepsGoodStatisticsForEmptyWindowsAndFailures() = checkPanel("Java")

    fun testKotlinProfilerKeepsGoodStatisticsForEmptyWindowsAndFailures() = checkPanel("Kotlin")

    private fun checkPanel(runtime: String) = runBlocking {
        val fixture = prepare(runtime, "ParserAction", profiledDecision = true)
        val parsed = requireNotNull(fixture.state.parsingResult)
        val predictionNs =
            parsed.parser?.parseInfo?.totalTimeInPrediction
                ?: requireNotNull(parsed.getKotlinProfilingStats()).totalTimeInPrediction
        assertTrue("The fixture must record prediction time", predictionNs > 0)
        val settings = requireNotNull(fixture.state.liveSettings)
        settings.benchmarkWarmupDurationMs = 0
        settings.benchmarkMeasurementDurationMs = 0
        val panel = ComposeProfilerPanel(project, testRootDisposable)
        try {
            fixture.reset(0)
            panel.setProfilerData(fixture.state, 12_345_678, fixture.input.inputText)
            val original = readPanel(panel, "getInitialStats") as ComposeProfilerPanel.InitialStats
            assertEquals(12.345678, original.parseTimeMs)
            assertEquals(2, original.numTokens)
            waitForBenchmark(panel)
            val empty = readPanel(panel, "getBenchmarkResult") as BenchmarkRunner.BenchmarkResult
            assertEquals(0, empty.iterations)
            assertFalse(readPanel(panel, "getBenchmarkFailed") as Boolean)
            assertSame(
                "An empty window must retain the preview statistics",
                original,
                readPanel(panel, "getInitialStats"),
            )

            val table =
                panel.javaClass
                    .getDeclaredMethod("createTableModel", java.lang.Boolean.TYPE)
                    .apply { isAccessible = true }
                    .invoke(panel, false) as com.antlr4plus.statistics.ProfilerTableDataModel
            assertEquals(1, table.rowCount)
            assertEquals(
                "An empty window must retain preview prediction-time scaling",
                java.math.BigDecimal("12.3457"),
                table.getValueAt(0, 2),
            )

            settings.benchmarkMeasurementDurationMs = 100
            fixture.reset(1)
            panel.runBenchmark()
            waitForBenchmark(panel)
            assertNull(readPanel(panel, "getBenchmarkResult"))
            assertTrue("The panel must report a parse failure", readPanel(panel, "getBenchmarkFailed") as Boolean)
            assertSame(
                "A failed benchmark must retain the preview statistics",
                original,
                readPanel(panel, "getInitialStats"),
            )

            fixture.reset(0)
            panel.runBenchmark()
            waitForBenchmark(panel)
            val success = readPanel(panel, "getBenchmarkResult") as BenchmarkRunner.BenchmarkResult
            assertTrue(success.iterations > 0)
            assertFalse(readPanel(panel, "getBenchmarkFailed") as Boolean)
            val updated = readPanel(panel, "getInitialStats") as ComposeProfilerPanel.InitialStats
            assertEquals(success.meanMs, updated.parseTimeMs)
            assertEquals(success.numTokens, updated.numTokens)
        } finally {
            panel.dispose()
        }
    }

    private fun waitForBenchmark(panel: ComposeProfilerPanel) {
        PlatformTestUtil.waitWithEventsDispatching(
            "Benchmark did not finish",
            { !(readPanel(panel, "isBenchmarkRunning") as Boolean) },
            30,
        )
    }

    // Observe the actual asynchronous panel state without exposing test-only production APIs.
    private fun readPanel(panel: ComposeProfilerPanel, getter: String): Any? =
        panel.javaClass.getDeclaredMethod(getter).apply { isAccessible = true }.invoke(panel)

    private fun oneMeasurement() =
        BenchmarkRunner.BenchmarkConfig(
            warmupDurationMs = 0,
            measurementDurationMs = 200,
            singleParseTimeoutMs = 0,
            parsingTimeoutMs = 10_000,
        )

    private class Fixture(
        val input: BenchmarkRunner.BenchmarkInput,
        val state: PreviewState,
        val countKey: String,
        val failKey: String,
    ) {
        fun reset(failAt: Int) {
            System.setProperty(countKey, "0")
            System.setProperty(failKey, failAt.toString())
        }

        fun calls(): Int = System.getProperty(countKey, "0").toInt()
    }

    private suspend fun prepare(runtime: String, location: String, profiledDecision: Boolean = false): Fixture {
        val countKey = "${root.fileName}.count"
        val failKey = "${root.fileName}.fail"
        Disposer.register(
            testRootDisposable,
            Disposable {
                System.clearProperty(countKey)
                System.clearProperty(failKey)
            },
        )
        val action =
            if (runtime == "Java") {
                "int call = Integer.parseInt(System.getProperty(\"$countKey\", \"0\")) + 1; " +
                    "System.setProperty(\"$countKey\", Integer.toString(call)); " +
                    "if (call == Integer.parseInt(System.getProperty(\"$failKey\", \"0\"))) " +
                    "throw new IllegalStateException(\"benchmark failure probe\");"
            } else {
                "val call = System.getProperty(\"$countKey\", \"0\").toInt() + 1; " +
                    "System.setProperty(\"$countKey\", call.toString()); " +
                    "check(call != System.getProperty(\"$failKey\", \"0\").toInt()) { \"benchmark failure probe\" }"
            }
        val members =
            if (location.endsWith("Constructor")) {
                val owner = if (location.startsWith("Lexer")) "lexer" else "parser"
                "@$owner::members { ${if (runtime == "Kotlin") "init" else ""} { $action } }"
            } else ""
        val parserAction = if (location == "ParserAction") "{$action}" else ""
        val lexerAction = if (location == "LexerAction") "{$action}" else ""
        val start = if (profiledDecision) "(WORD | WORD WORD)" else "WORD"
        val grammarPath = root.resolve("FailureProbe.g4")
        Files.writeString(
            grammarPath,
            "grammar FailureProbe;\n$members\nstart: $start $parserAction EOF;\nWORD: [a-z]+ $lexerAction;",
        )
        Files.writeString(root.resolve("input.g4p"), "ok")
        val files = LocalFileSystem.getInstance()
        val grammarFile = requireNotNull(files.refreshAndFindFileByNioFile(grammarPath))
        val inputFile = requireNotNull(files.refreshAndFindFileByNioFile(root.resolve("input.g4p")))
        PsiTestUtil.addContentRoot(module, inputFile.parent)
        val service = project.getService(PreviewService::class.java)
        val state = service.state
        state.g4pInputFile = inputFile
        state.grammarFile = grammarFile
        state.startRuleName = "start"
        val outputRoot = root.resolve("benchmark")
        requireNotNull(state.liveSettings).apply {
            libDir = ""
            previewRuntime = runtime
            outputDir = outputRoot.toString()
            parsingTimeoutMs = 10_000
        }
        val console = com.antlr4plus.error.ComposeAntlrErrorConsolePanel(project)
        service.antlrConsolePanel = console
        val (lexerGrammar, parserGrammar) =
            requireNotNull(ParsingUtils.loadGrammars(project, grammarFile)) { console.getErrorsForExport().toString() }
        state.lexerGrammar = requireNotNull(lexerGrammar)
        state.grammar = requireNotNull(parserGrammar)
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
                parserGrammar,
                lexerGrammar,
                "start",
                grammarFile,
                "ok",
                generated.toFile(),
                project,
            )
        assertTrue(
            "Preview failed: $parsed ${console.getErrorsForExport()}",
            parsed is CompiledParsingService.CompiledParseResult.Success,
        )
        state.parsingResult = (parsed as CompiledParsingService.CompiledParseResult.Success).result
        return Fixture(requireNotNull(BenchmarkRunner.captureInput(state)), state, countKey, failKey)
    }
}
