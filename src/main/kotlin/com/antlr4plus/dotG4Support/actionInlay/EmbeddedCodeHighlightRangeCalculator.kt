package com.antlr4plus.dotG4Support.actionInlay

import com.antlr4plus.parsing.embedded.EmbeddedCode

internal object EmbeddedCodeHighlightRangeCalculator {

    /** Includes surrounding braces or brackets when body offsets are available. */
    fun calculate(block: EmbeddedCode, fileContent: CharSequence): IntRange? =
        calculateFromBodyOffsets(block, fileContent.length) ?: calculateFromLineColumns(block, fileContent)

    private fun calculateFromBodyOffsets(block: EmbeddedCode, textLength: Int): IntRange? {
        val bodyStart = block.bodyStartOffset
        val bodyEndExclusive = block.bodyEndOffsetExclusive
        if (bodyStart !in 0..bodyEndExclusive || bodyEndExclusive > textLength) {
            return null
        }

        val startOffset = (bodyStart - 1).coerceAtLeast(0)
        val endOffsetExclusive = (bodyEndExclusive + 1).coerceAtMost(textLength)
        if (endOffsetExclusive <= startOffset) {
            return null
        }

        return startOffset until endOffsetExclusive
    }

    private fun calculateFromLineColumns(
        block: EmbeddedCode,
        fileContent: CharSequence,
    ): IntRange? {
        val lineStarts = computeLineStarts(fileContent)
        val startOffset = getOffset(lineStarts, block.startLine, block.startColumn, fileContent.length) ?: return null
        val endOffset = getOffset(lineStarts, block.endLine, block.endColumn, fileContent.length) ?: return null
        if (endOffset <= startOffset) {
            return null
        }

        return startOffset until endOffset
    }

    private fun computeLineStarts(fileContent: CharSequence): IntArray {
        if (fileContent.isEmpty()) {
            return intArrayOf(0)
        }

        val starts = ArrayList<Int>()
        starts.add(0)
        for (index in fileContent.indices) {
            if (fileContent[index] == '\n' && index + 1 <= fileContent.length) {
                starts.add(index + 1)
            }
        }
        return starts.toIntArray()
    }

    private fun getOffset(
        lineStarts: IntArray,
        lineNumber: Int,
        column: Int,
        textLength: Int,
    ): Int? {
        if (lineNumber < 1 || lineNumber > lineStarts.size) {
            return null
        }

        val lineIndex = lineNumber - 1
        val lineStart = lineStarts[lineIndex]
        val lineEndExclusive = if (lineIndex + 1 < lineStarts.size) lineStarts[lineIndex + 1] - 1 else textLength

        return (lineStart + column).coerceIn(lineStart, lineEndExclusive)
    }
}
