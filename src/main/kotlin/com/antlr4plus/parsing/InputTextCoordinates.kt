package com.antlr4plus.parsing

import com.antlr4plus.util.rethrowFatalOrCancellation
import java.lang.reflect.InvocationTargetException
import java.util.WeakHashMap
import org.antlr.v4.runtime.CharStream
import org.antlr.v4.runtime.Token
import org.antlr.v4.runtime.misc.Interval

/** Converts an immutable ANTLR input snapshot to the editor's UTF-16 offsets. */
internal class InputTextCoordinates(val text: String, codePointIndices: Boolean = true) {
    private val offsets: IntArray? =
        if (codePointIndices && text.codePointCount(0, text.length) != text.length) {
            IntArray(text.codePointCount(0, text.length) + 1).also { result ->
                for (i in 1 until result.size) {
                    result[i] = result[i - 1] + Character.charCount(text.codePointAt(result[i - 1]))
                }
            }
        } else null

    fun toEditor(offset: Int): Int? =
        if (offsets == null) offset.takeIf { it in 0..text.length } else offsets.getOrNull(offset)

    fun toNative(editorOffset: Int): Int? {
        if (editorOffset !in 0..text.length) return null
        val positions = offsets ?: return editorOffset
        val index = positions.binarySearch(editorOffset)
        return if (index >= 0) index else -index - 2
    }

    fun inclusiveRange(start: Int, stop: Int): Pair<Int, Int>? {
        if (start < 0 || stop < start - 1 || stop == Int.MAX_VALUE) return null
        return (toEditor(start) ?: return null) to ((toEditor(stop + 1) ?: return null) - 1)
    }

    private val lineStarts by lazy {
        buildList {
            add(0)
            text.forEachIndexed { index, ch -> if (ch == '\n') add(index + 1) }
        }
    }

    /** ANTLR lines are one-based and columns count code points. */
    fun lineColumnOffset(line: Int, column: Int): Int? {
        val start = lineStarts.getOrNull(line - 1) ?: return null
        val end = lineStarts.getOrNull(line)?.minus(1) ?: text.length
        if (column < 0 || column > text.codePointCount(start, end)) return null
        return text.offsetByCodePoints(start, column)
    }

    fun characterEnd(start: Int): Int =
        if (start == text.length) start else start + Character.charCount(text.codePointAt(start))
}

// Values retain the text, not the stream or its runtime class loader.
private val inputCoordinates = WeakHashMap<Any, InputTextCoordinates>()

internal fun coordinatesForInput(input: CharStream?): InputTextCoordinates? {
    input ?: return null
    return synchronized(inputCoordinates) {
        inputCoordinates.getOrPut(input) {
            val text = input.getText(Interval.of(0, input.size() - 1))
            InputTextCoordinates(text, codePointIndices = input.size() != text.length)
        }
    }
}

internal fun coordinatesForKotlinToken(token: Any): InputTextCoordinates? =
    try {
        val input = token.javaClass.getMethod("getInputStream").invoke(token)
        if (input == null) null
        else
            synchronized(inputCoordinates) {
                inputCoordinates.getOrPut(input) {
                    val loader = input.javaClass.classLoader
                    val streamClass = loader.loadClass("org.antlr.v4.kotlinruntime.CharStream")
                    val intervalClass = loader.loadClass("org.antlr.v4.kotlinruntime.misc.Interval")
                    val size = streamClass.getMethod("size").invoke(input) as Int
                    val interval =
                        intervalClass
                            .getConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                            .newInstance(0, size - 1)
                    val text = streamClass.getMethod("getText", intervalClass).invoke(input, interval) as String
                    InputTextCoordinates(text, codePointIndices = size != text.length)
                }
            }
    } catch (failure: ReflectiveOperationException) {
        rethrowFatalOrCancellation((failure as? InvocationTargetException)?.targetException ?: failure)
        null
    }

internal fun editorTokenRange(token: Token): Pair<Int, Int>? =
    coordinatesForInput(token.inputStream)?.inclusiveRange(token.startIndex, token.stopIndex)
        ?: (token.startIndex to token.stopIndex).takeIf { token.inputStream == null }
