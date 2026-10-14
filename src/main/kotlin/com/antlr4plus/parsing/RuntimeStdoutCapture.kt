package com.antlr4plus.parsing

import com.antlr4plus.util.rethrowIfControlFlow
import com.intellij.openapi.diagnostic.Logger
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.PrintStream
import java.nio.charset.Charset
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import org.jetbrains.annotations.TestOnly

/**
 * Tees process output to bounded per-thread capture scopes. Nested scopes restore their outer sink. Removes the shared
 * tee after the last scope so it does not retain the plugin classloader.
 */
object RuntimeStdoutCapture {

    private val LOG = Logger.getInstance(RuntimeStdoutCapture::class.java)

    internal const val MAX_CAPTURED_BYTES = 1_048_576
    internal const val MAX_CAPTURED_LINES = 4_096

    private data class CaptureFrame(
        val sink: (String) -> Unit,
        val bytes: ByteArrayOutputStream = ByteArrayOutputStream(128),
        var capturedBytes: Int = 0,
        var deliveredLines: Int = 0,
        var truncated: Boolean = false,
    )

    private class CaptureState {
        val frames = ArrayDeque<CaptureFrame>()
    }

    private val captures = ConcurrentHashMap<Thread, CaptureState>()
    private val deliveringToSink = ThreadLocal<Boolean>()

    private var activeCaptureCount = 0
    private var originalOut: PrintStream? = null
    private var originalErr: PrintStream? = null
    private var installedOut: PrintStream? = null
    private var installedErr: PrintStream? = null
    private var captureCharset: Charset = Charset.defaultCharset()

    /** Captures lines on the current thread and flushes any trailing partial line when block exits. */
    fun <T> runWithCaptureOnCurrentThread(sink: (String) -> Unit, block: () -> T): T {
        if (!beginCapture()) return block()

        val thread = Thread.currentThread()
        val state = captures.computeIfAbsent(thread) { CaptureState() }
        val frame = CaptureFrame(sink)
        synchronized(state) {
            state.frames.addLast(frame)
        }

        try {
            return block()
        } finally {
            val trailingLine =
                synchronized(state) {
                    check(state.frames.peekLast() === frame) {
                        "Runtime stdout capture scopes closed out of order"
                    }
                    state.frames.removeLast()
                    decodeBufferedLine(frame, includeEmpty = false)
                }
            trailingLine?.let { deliver(frame.sink, it) }
            synchronized(state) {
                if (state.frames.isEmpty()) captures.remove(thread, state)
            }
            endCapture()
        }
    }

    @Synchronized
    private fun beginCapture(): Boolean {
        if (activeCaptureCount == 0 && !installLocked()) return false
        activeCaptureCount++
        return true
    }

    @Synchronized
    private fun endCapture() {
        check(activeCaptureCount > 0) { "Unbalanced runtime stdout capture scope" }
        activeCaptureCount--
        if (activeCaptureCount == 0) uninstallLocked()
    }

    /** Caller must hold this object's monitor. */
    private fun installLocked(): Boolean {
        val currentOut = System.out
        val currentErr = System.err
        val charset = Charset.defaultCharset()
        val teeOut = PrintStream(TeeOutputStream(currentOut), true, charset)
        val teeErr = PrintStream(TeeOutputStream(currentErr), true, charset)

        return try {
            System.setOut(teeOut)
            try {
                System.setErr(teeErr)
            } catch (failure: Throwable) {
                if (System.out === teeOut) System.setOut(currentOut)
                throw failure
            }
            originalOut = currentOut
            originalErr = currentErr
            installedOut = teeOut
            installedErr = teeErr
            captureCharset = charset
            true
        } catch (failure: Throwable) {
            rethrowIfControlFlow(failure)
            teeOut.close()
            teeErr.close()
            LOG.warn("RuntimeStdoutCapture: failed to install scoped tee streams", failure)
            false
        }
    }

    /** Caller must hold this object's monitor. */
    private fun uninstallLocked() {
        val teeOut = installedOut
        val teeErr = installedErr
        val restoreOut = originalOut
        val restoreErr = originalErr
        try {
            // Preserve streams installed by other components during capture.
            if (teeOut != null && restoreOut != null && System.out === teeOut) System.setOut(restoreOut)
            if (teeErr != null && restoreErr != null && System.err === teeErr) System.setErr(restoreErr)
        } catch (failure: Throwable) {
            rethrowIfControlFlow(failure)
            LOG.warn("RuntimeStdoutCapture: failed to restore process streams", failure)
        } finally {
            teeOut?.close()
            teeErr?.close()
            originalOut = null
            originalErr = null
            installedOut = null
            installedErr = null
        }
    }

    private fun captureByte(byte: Byte) {
        if (deliveringToSink.get() == true) return
        val state = captures[Thread.currentThread()] ?: return
        val frameAndLines =
            synchronized(state) {
                val frame = state.frames.peekLast() ?: return
                val lines =
                    when {
                        frame.truncated -> emptyList()
                        frame.capturedBytes >= MAX_CAPTURED_BYTES -> truncate(frame, "byte limit")
                        frame.deliveredLines >= MAX_CAPTURED_LINES -> truncate(frame, "line limit")
                        else -> {
                            frame.capturedBytes++
                            if (byte == '\n'.code.toByte()) {
                                frame.deliveredLines++
                                listOf(
                                    requireNotNull(decodeBufferedLine(frame, includeEmpty = true)),
                                )
                            } else {
                                frame.bytes.write(byte.toInt())
                                emptyList()
                            }
                        }
                    }
                frame to lines
            }
        frameAndLines.second.forEach { line -> deliver(frameAndLines.first.sink, line) }
    }

    private fun truncate(frame: CaptureFrame, reason: String): List<String> {
        frame.truncated = true
        val partialLine = decodeBufferedLine(frame, includeEmpty = false)
        val marker =
            "[ANTLR4+ runtime output truncated: $reason " +
                "(${frame.capturedBytes} bytes, ${frame.deliveredLines} lines)]"
        return listOfNotNull(partialLine) + marker
    }

    private fun decodeBufferedLine(frame: CaptureFrame, includeEmpty: Boolean): String? {
        val bytes = frame.bytes.toByteArray()
        frame.bytes.reset()
        var length = bytes.size
        if (length > 0 && bytes[length - 1] == '\r'.code.toByte()) length--
        if (length == 0 && !includeEmpty) return null
        return String(bytes, 0, length, captureCharset)
    }

    private fun deliver(sink: (String) -> Unit, line: String) {
        deliveringToSink.set(true)
        try {
            sink(line)
        } catch (failure: Throwable) {
            rethrowIfControlFlow(failure)
            // Ignore ordinary sink failures after propagating fatal and cancellation exceptions.
            LOG.debug("RuntimeStdoutCapture sink threw: ${failure.message}")
        } finally {
            deliveringToSink.remove()
        }
    }

    @TestOnly
    @Synchronized
    internal fun resetForTest(processOut: PrintStream, processErr: PrintStream) {
        captures.clear()
        activeCaptureCount = 0
        installedOut?.close()
        installedErr?.close()
        installedOut = null
        installedErr = null
        originalOut = null
        originalErr = null
        System.setOut(processOut)
        System.setErr(processErr)
        deliveringToSink.remove()
    }

    private class TeeOutputStream(private val delegate: OutputStream) : OutputStream() {
        override fun write(b: Int) {
            delegate.write(b)
            captureByte(b.toByte())
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            delegate.write(buffer, offset, length)
            for (index in offset until offset + length) captureByte(buffer[index])
        }

        override fun write(buffer: ByteArray) {
            delegate.write(buffer)
            for (byte in buffer) captureByte(byte)
        }

        override fun flush() = delegate.flush()

        override fun close() {
            // Never close the IDE process stream.
        }
    }
}
