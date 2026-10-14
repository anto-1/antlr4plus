package com.antlr4plus.parsing

import java.io.OutputStream
import java.io.PrintStream
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import junit.framework.TestCase

class RuntimeStdoutCaptureTest : TestCase() {

    fun `test captured stdout and stderr preserve Unicode and partial lines`() = withCleanStreams {
        val captured = mutableListOf<String>()

        RuntimeStdoutCapture.runWithCaptureOnCurrentThread(captured::add) {
            System.out.write("Grüße 世界 🚀\n".toByteArray(Charsets.UTF_8))
            System.err.write("ошибка без newline".toByteArray(Charsets.UTF_8))
        }

        assertEquals(listOf("Grüße 世界 🚀", "ошибка без newline"), captured)
    }

    fun `test nested capture restores the outer sink`() = withCleanStreams {
        val outer = mutableListOf<String>()
        val inner = mutableListOf<String>()

        RuntimeStdoutCapture.runWithCaptureOnCurrentThread(outer::add) {
            System.out.println("outer before")
            RuntimeStdoutCapture.runWithCaptureOnCurrentThread(inner::add) {
                System.err.println("inner")
            }
            System.out.println("outer after")
        }

        assertEquals(listOf("outer before", "outer after"), outer)
        assertEquals(listOf("inner"), inner)
    }

    fun `test concurrent captures never cross route output`() = withCleanStreams {
        val first = Collections.synchronizedList(mutableListOf<String>())
        val second = Collections.synchronizedList(mutableListOf<String>())
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val futures =
                listOf(
                    executor.submit {
                        RuntimeStdoutCapture.runWithCaptureOnCurrentThread(first::add) {
                            ready.countDown()
                            assertTrue(start.await(5, TimeUnit.SECONDS))
                            repeat(100) { System.out.println("first-$it") }
                        }
                    },
                    executor.submit {
                        RuntimeStdoutCapture.runWithCaptureOnCurrentThread(second::add) {
                            ready.countDown()
                            assertTrue(start.await(5, TimeUnit.SECONDS))
                            repeat(100) { System.err.println("second-$it") }
                        }
                    },
                )
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            futures.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }

        assertEquals((0 until 100).map { "first-$it" }, first)
        assertEquals((0 until 100).map { "second-$it" }, second)
    }

    fun `test capture restores process streams when the outermost scope ends`() =
        withOriginalStreams { originalOut, originalErr ->
            RuntimeStdoutCapture.runWithCaptureOnCurrentThread({}) {
                assertNotSame(originalOut, System.out)
                assertNotSame(originalErr, System.err)
            }

            assertSame("System.out retained the plugin capture wrapper", originalOut, System.out)
            assertSame("System.err retained the plugin capture wrapper", originalErr, System.err)
        }

    fun `test exception flushes the current partial line and removes its sink`() = withCleanStreams {
        val captured = mutableListOf<String>()

        val failure = runCatching {
            RuntimeStdoutCapture.runWithCaptureOnCurrentThread(captured::add) {
                System.out.print("partial before failure")
                throw IllegalStateException("boom")
            }
        }
            .exceptionOrNull()
        System.out.println("outside capture")

        assertTrue(failure is IllegalStateException)
        assertEquals(listOf("partial before failure"), captured)
    }

    fun `test capture bounds unterminated output before it can exhaust the heap`() {
        val processOut = System.out
        val processErr = System.err
        val discardedOut = PrintStream(OutputStream.nullOutputStream())
        val discardedErr = PrintStream(OutputStream.nullOutputStream())
        System.setOut(discardedOut)
        System.setErr(discardedErr)
        try {
            val captured = mutableListOf<String>()
            RuntimeStdoutCapture.runWithCaptureOnCurrentThread(captured::add) {
                System.out.write(
                    ByteArray(RuntimeStdoutCapture.MAX_CAPTURED_BYTES + 64) { 'x'.code.toByte() },
                )
            }

            assertTrue(
                "Capture did not report truncation: ${captured.map(String::length)}",
                captured.last().contains("truncated"),
            )
            assertTrue(
                "Captured payload exceeded its configured bound",
                captured.dropLast(1).sumOf(String::length) <= RuntimeStdoutCapture.MAX_CAPTURED_BYTES,
            )
        } finally {
            RuntimeStdoutCapture.resetForTest(processOut, processErr)
            discardedOut.close()
            discardedErr.close()
        }
    }

    fun `test capture bounds newline floods before they can exhaust the console`() {
        val processOut = System.out
        val processErr = System.err
        val discardedOut = PrintStream(OutputStream.nullOutputStream())
        val discardedErr = PrintStream(OutputStream.nullOutputStream())
        System.setOut(discardedOut)
        System.setErr(discardedErr)
        try {
            val captured = mutableListOf<String>()
            RuntimeStdoutCapture.runWithCaptureOnCurrentThread(captured::add) {
                repeat(RuntimeStdoutCapture.MAX_CAPTURED_LINES + 64) { index ->
                    System.out.println("line-$index")
                }
            }

            assertEquals(RuntimeStdoutCapture.MAX_CAPTURED_LINES + 1, captured.size)
            assertTrue(
                "Capture did not report line truncation",
                captured.last().contains("line limit"),
            )
        } finally {
            RuntimeStdoutCapture.resetForTest(processOut, processErr)
            discardedOut.close()
            discardedErr.close()
        }
    }

    private fun withCleanStreams(action: () -> Unit) {
        val originalOut = System.out
        val originalErr = System.err
        try {
            action()
        } finally {
            RuntimeStdoutCapture.resetForTest(originalOut, originalErr)
        }
    }

    private fun withOriginalStreams(action: (PrintStream, PrintStream) -> Unit) {
        val originalOut = System.out
        val originalErr = System.err
        try {
            action(originalOut, originalErr)
        } finally {
            RuntimeStdoutCapture.resetForTest(originalOut, originalErr)
        }
    }
}
