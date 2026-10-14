package com.antlr4plus.error

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ComposeConsoleConcurrencyTest : BasePlatformTestCase() {

    fun `test ANTLR console counts diagnostics without treating informational output as errors`() {
        val console = ComposeAntlrErrorConsolePanel(project)
        console.appendMultiLine("Generating parser\nOutput written", isError = false)
        assertEquals(0, console.getLineCount())

        console.appendMultiLine("Warning: Missing token\n  details", isError = false)
        console.appendLine("Expected 0 errors but compilation failed")
        assertEquals(3, console.getLineCount())
        assertEquals(
            listOf(
                "Generating parser",
                "Output written",
                "Warning: Missing token",
                "  details",
                "Expected 0 errors but compilation failed",
            ),
            console.getErrorsForExport(),
        )

        console.clear()
        assertEquals(0, console.getLineCount())
        assertNull(console.getErrorsForExport())
    }

    fun `test ANTLR console trims severity together with retained output`() {
        val console = ComposeAntlrErrorConsolePanel(project)
        console.appendLine("Old error")
        console.appendMultiLine(List(10_000) { "info-$it" }.joinToString("\n"), isError = false)
        assertEquals(0, console.getLineCount())
        assertEquals(10_000, console.getErrorsForExport()!!.size)

        console.appendLine("New error")
        assertEquals(1, console.getLineCount())
        assertEquals("New error", console.getErrorsForExport()!!.last())
    }

    fun `test runtime console retains concurrent output without quadratic list replacement`() {
        val console = ComposeRuntimeConsolePanel(project)
        val workers = 8
        val linesPerWorker = 500
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(workers)
        try {
            val tasks =
                (0 until workers).map { worker ->
                    executor.submit {
                        start.await()
                        repeat(linesPerWorker) { line ->
                            console.appendLine("worker-$worker-line-$line")
                        }
                    }
                }
            start.countDown()
            tasks.forEach { it.get(10, TimeUnit.SECONDS) }

            val output = requireNotNull(console.getErrorsForExport())
            assertEquals(workers * linesPerWorker, output.size)
            assertEquals(output.size, output.toSet().size)
        } finally {
            executor.shutdownNow()
        }
    }

    fun `test runtime console bounds output retained from untrusted actions`() {
        val console = ComposeRuntimeConsolePanel(project)
        repeat(11_000) { console.appendLine("line-$it") }

        val output = requireNotNull(console.getErrorsForExport())
        assertEquals(10_000, output.size)
        assertEquals("line-1000", output.first())
        assertEquals("line-10999", output.last())
    }
}
