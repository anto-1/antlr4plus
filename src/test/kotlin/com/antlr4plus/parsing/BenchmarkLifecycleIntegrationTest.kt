package com.antlr4plus.parsing

import com.antlr4plus.benchmark.BenchmarkRunner
import com.antlr4plus.previewService.PreviewService
import com.antlr4plus.state.PreviewState
import com.antlr4plus.statistics.ComposeProfilerPanel
import com.intellij.openapi.Disposable
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.antlr.v4.Tool

/** Observes actual coroutine termination independently of the panel's running indicator. */
class BenchmarkLifecycleIntegrationTest : BasePlatformTestCase() {
    private lateinit var root: Path

    override fun setUp() {
        super.setUp()
        root = Files.createTempDirectory("antlr4plus-benchmark-lifecycle-")
    }

    override fun tearDown() {
        try {
            if (::root.isInitialized) {
                CompiledParsingService.invalidateCache(root.resolve("LifecycleProbe.g4").toString())
                root.toFile().deleteRecursively()
            }
        } finally {
            super.tearDown()
        }
    }

    fun testJavaControlFlowFailureFinishesTheBenchmark() = checkFailure("Java", ProcessCanceledException())

    fun testKotlinControlFlowFailureFinishesTheBenchmark() = checkFailure("Kotlin", ProcessCanceledException())

    fun testJavaCoroutineCancellationRemainsCancellation() =
        checkFailure("Java", CancellationException("cancel action"))

    fun testKotlinCoroutineCancellationRemainsCancellation() =
        checkFailure("Kotlin", CancellationException("cancel action"))

    fun testJavaFatalFailureFinishesAndPropagates() = checkFailure("Java", ProbeFatalError())

    fun testKotlinFatalFailureFinishesAndPropagates() = checkFailure("Kotlin", ProbeFatalError())

    private fun checkFailure(runtime: String, expected: Throwable) = runBlocking {
        val fixture = prepare(runtime)
        val observed = ConcurrentLinkedQueue<Throwable>()
        val panel = createPanel(observed)
        try {
            fixture.action.set(Runnable { throw expected })
            publish(panel, fixture)
            val job = benchmarkJob(panel)
            val completion = AtomicReference<Throwable?>()
            job.invokeOnCompletion { completion.set(it) }
            val original = readPanel(panel, "getInitialStats")
            waitForJob(job)
            assertFalse("A terminal benchmark must stop its running indicator", isRunning(panel))
            assertTrue("A cancellation/control-flow/fatal failure must remain exceptional", job.isCancelled)
            assertFailureIdentity(expected, completion.get())
            if (expected is CancellationException) assertTrue(observed.isEmpty())
            else {
                assertEquals(1, observed.size)
                assertFailureIdentity(expected, observed.single())
            }
            assertNull(readPanel(panel, "getBenchmarkResult"))
            assertFalse(
                "Control flow must not become an ordinary parse failure",
                readPanel(panel, "getBenchmarkFailed") as Boolean,
            )
            assertSame(original, readPanel(panel, "getInitialStats"))
            fixture.action.set(Runnable {})
            panel.runBenchmark()
            assertSuccessfulEmptyWindow(panel)
        } finally {
            panel.dispose()
        }
    }

    fun testJavaCancelledJobCleansUpInACancelledContext() = checkCancellation("Java", explicit = false)

    fun testKotlinCancelledJobCleansUpInACancelledContext() = checkCancellation("Kotlin", explicit = false)

    fun testJavaExplicitCancellationKeepsStatistics() = checkCancellation("Java", explicit = true)

    fun testKotlinExplicitCancellationKeepsStatistics() = checkCancellation("Kotlin", explicit = true)

    private fun checkCancellation(runtime: String, explicit: Boolean) = runBlocking {
        val fixture = prepare(runtime)
        val gate = ParseGate()
        val panel = createPanel()
        var job: Job? = null
        try {
            fixture.action.set(gate)
            publish(panel, fixture)
            job = benchmarkJob(panel)
            gate.waitUntilEntered()
            val original = readPanel(panel, "getInitialStats")
            if (explicit) {
                panel.cancelBenchmark()
                assertFalse(isRunning(panel))
            } else job.cancel(CancellationException("cancel benchmark job"))
            gate.release.countDown()
            waitForJob(job)
            assertTrue(job.isCancelled)
            assertFalse("Cancelled jobs must release the running indicator", isRunning(panel))
            assertNull(readPanel(panel, "getBenchmarkResult"))
            assertFalse(readPanel(panel, "getBenchmarkFailed") as Boolean)
            assertSame(original, readPanel(panel, "getInitialStats"))
            fixture.action.set(Runnable {})
            panel.runBenchmark()
            assertSuccessfulEmptyWindow(panel)
        } finally {
            gate.release.countDown()
            panel.dispose()
            job?.let(::waitForJob)
        }
    }

    fun testJavaDiscardedResultStillFinishesItsRun() = checkStaleResult("Java")

    fun testKotlinDiscardedResultStillFinishesItsRun() = checkStaleResult("Kotlin")

    private fun checkStaleResult(runtime: String) = runBlocking {
        val fixture = prepare(runtime)
        val parsed = fixture.state.parsingResult
        val gate = ParseGate()
        val panel = createPanel()
        var job: Job? = null
        try {
            fixture.action.set(gate)
            publish(panel, fixture)
            job = benchmarkJob(panel)
            gate.waitUntilEntered()
            val original = readPanel(panel, "getInitialStats")
            fixture.state.parsingResult = null
            gate.release.countDown()
            waitForJob(job)
            assertFalse(job.isCancelled)
            assertNull("A stale result must not be published", readPanel(panel, "getBenchmarkResult"))
            assertFalse("Discarding a result must still finish the run", isRunning(panel))
            assertFalse(readPanel(panel, "getBenchmarkFailed") as Boolean)
            assertSame(original, readPanel(panel, "getInitialStats"))
            fixture.state.parsingResult = parsed
            fixture.action.set(Runnable {})
            panel.runBenchmark()
            assertSuccessfulEmptyWindow(panel)
        } finally {
            gate.release.countDown()
            panel.dispose()
            job?.let(::waitForJob)
        }
    }

    fun testJavaOldRunCannotFinishANewerRun() = checkSupersededRun("Java")

    fun testKotlinOldRunCannotFinishANewerRun() = checkSupersededRun("Kotlin")

    private fun checkSupersededRun(runtime: String) = runBlocking {
        val fixture = prepare(runtime)
        val oldGate = ParseGate()
        val newGate = ParseGate()
        val panel = createPanel()
        var oldJob: Job? = null
        var newJob: Job? = null
        try {
            fixture.action.set(oldGate)
            publish(panel, fixture)
            oldJob = benchmarkJob(panel)
            oldGate.waitUntilEntered()
            panel.cancelBenchmark()
            fixture.action.set(newGate)
            panel.runBenchmark()
            newJob = benchmarkJob(panel)
            assertNotSame(oldJob, newJob)
            newGate.waitUntilEntered()
            oldGate.release.countDown()
            waitForJob(oldJob)
            assertTrue(oldJob.isCancelled)
            assertFalse(newJob.isCompleted)
            assertTrue("An old run's cleanup must preserve the new running indicator", isRunning(panel))
            assertNull(readPanel(panel, "getBenchmarkResult"))
            assertFalse(readPanel(panel, "getBenchmarkFailed") as Boolean)
            newGate.release.countDown()
            assertSuccessfulEmptyWindow(panel)
        } finally {
            oldGate.release.countDown()
            newGate.release.countDown()
            panel.dispose()
            oldJob?.let(::waitForJob)
            newJob?.let(::waitForJob)
        }
    }

    fun testJavaDisposedPanelCannotStartAnotherBenchmark() = checkDisposedPanel("Java")

    fun testKotlinDisposedPanelCannotStartAnotherBenchmark() = checkDisposedPanel("Kotlin")

    private fun checkDisposedPanel(runtime: String) = runBlocking {
        val fixture = prepare(runtime)
        val panel = createPanel()
        try {
            publish(panel, fixture)
            assertSuccessfulEmptyWindow(panel)
            val job = benchmarkJob(panel)
            val stats = readPanel(panel, "getInitialStats")
            val result = readPanel(panel, "getBenchmarkResult")
            panel.dispose()
            panel.runBenchmark()
            assertFalse("A disposed panel cannot start a benchmark", isRunning(panel))
            assertSame(job, benchmarkJob(panel))
            assertSame(stats, readPanel(panel, "getInitialStats"))
            assertSame(result, readPanel(panel, "getBenchmarkResult"))
        } finally {
            panel.dispose()
        }
    }

    private fun publish(panel: ComposeProfilerPanel, fixture: Fixture) =
        panel.setProfilerData(fixture.state, 12_345_678, "ok")

    private fun assertSuccessfulEmptyWindow(panel: ComposeProfilerPanel) {
        waitForJob(benchmarkJob(panel))
        assertFalse(isRunning(panel))
        assertFalse(readPanel(panel, "getBenchmarkFailed") as Boolean)
        assertEquals(0, (readPanel(panel, "getBenchmarkResult") as BenchmarkRunner.BenchmarkResult).iterations)
    }

    private fun waitForJob(job: Job) {
        PlatformTestUtil.waitWithEventsDispatching("Benchmark job did not terminate", { job.isCompleted }, 30)
    }

    private fun isRunning(panel: ComposeProfilerPanel): Boolean = readPanel(panel, "isBenchmarkRunning") as Boolean

    private fun readPanel(panel: ComposeProfilerPanel, getter: String): Any? =
        panel.javaClass.getDeclaredMethod(getter).apply { isAccessible = true }.invoke(panel)

    private fun benchmarkJob(panel: ComposeProfilerPanel): Job =
        panel.javaClass.getDeclaredField("benchmarkJob").apply { isAccessible = true }.get(panel) as Job

    private fun createPanel(
        observed: ConcurrentLinkedQueue<Throwable> = ConcurrentLinkedQueue(),
    ): ComposeProfilerPanel {
        val panel = ComposeProfilerPanel(project, testRootDisposable)
        // Keep the actual dispatcher and supervisor while observing propagated failures without global handlers.
        val field = panel.javaClass.getDeclaredField("benchmarkScope").apply { isAccessible = true }
        val scope = field.get(panel) as CoroutineScope
        val handler = CoroutineExceptionHandler { _, failure -> observed.add(failure) }
        field.set(panel, CoroutineScope(scope.coroutineContext + handler))
        return panel
    }

    private fun assertFailureIdentity(expected: Throwable, actual: Throwable?) {
        // Coroutine stack-trace recovery may wrap an exception in a same-type copy whose cause is the original.
        assertTrue("Expected the original failure, received $actual", actual === expected || actual?.cause === expected)
    }

    private class ProbeFatalError : VirtualMachineError("synthetic fatal failure")

    private class ParseGate : Runnable {
        private val entered = CountDownLatch(1)
        val release = CountDownLatch(1)

        override fun run() {
            entered.countDown()
            check(release.await(30, TimeUnit.SECONDS)) { "Test did not release the parser" }
        }

        fun waitUntilEntered() {
            PlatformTestUtil.waitWithEventsDispatching("Parser did not enter its action", { entered.count == 0L }, 30)
        }
    }

    private data class Fixture(val state: PreviewState, val action: AtomicReference<Runnable>)

    private suspend fun prepare(runtime: String): Fixture {
        val key = "${root.fileName}.action"
        val action = AtomicReference(Runnable {})
        System.getProperties()[key] = Runnable { action.get().run() }
        Disposer.register(testRootDisposable, Disposable { System.getProperties().remove(key) })
        val code =
            if (runtime == "Java") "((Runnable) System.getProperties().get(\"$key\")).run();"
            else "(System.getProperties()[\"$key\"] as Runnable).run()"
        val grammarPath = root.resolve("LifecycleProbe.g4")
        Files.writeString(grammarPath, "grammar LifecycleProbe;\nstart: WORD { $code } EOF;\nWORD: [a-z]+;")
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
        val outputRoot = root.resolve("generated")
        requireNotNull(state.liveSettings).apply {
            libDir = ""
            previewRuntime = runtime
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
        return Fixture(state, action)
    }
}
