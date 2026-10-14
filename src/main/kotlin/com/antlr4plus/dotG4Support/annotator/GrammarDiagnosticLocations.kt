package com.antlr4plus.dotG4Support.annotator

import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import org.antlr.runtime.ANTLRFileStream
import org.antlr.runtime.CommonToken
import org.antlr.runtime.Token

/** Converts ANTLR coordinates: one-based lines and zero-based columns. */
internal fun getTextRangeFromLineColumn(
    document: Document,
    line: Int,
    column: Int,
    file: PsiFile,
): TextRange? {
    val lineIndex = line - 1
    if (lineIndex < 0 || lineIndex >= document.lineCount) return null

    val lineStartOffset = document.getLineStartOffset(lineIndex)
    val lineEndOffset = document.getLineEndOffset(lineIndex)
    val startOffset = (lineStartOffset + column).coerceIn(lineStartOffset, lineEndOffset)

    val text = document.getText(TextRange(startOffset, lineEndOffset))
    val endOffset =
        if (text.isNotEmpty()) {
            val tokenEnd = text.indexOfFirst { !it.isLetterOrDigit() && it != '_' }
            if (tokenEnd > 0) startOffset + tokenEnd else lineEndOffset.coerceAtMost(startOffset + 20)
        } else {
            startOffset + 1
        }

    return TextRange(startOffset, endOffset.coerceAtMost(file.textLength))
}

internal fun getTokenRange(ct: CommonToken, file: PsiFile): TextRange {
    var startIndex = ct.startIndex
    var stopIndex = ct.stopIndex

    if (startIndex >= file.textLength) {
        // EOF diagnostics may point just beyond the document.
        stopIndex = file.textLength - 1
        startIndex = stopIndex
    }

    if (startIndex < 0) {
        startIndex = 0
    }

    return TextRange(startIndex, stopIndex + 1)
}

internal fun tokenBelongsToFile(t: Token, file: PsiFile): Boolean {
    val inputStream = t.inputStream
    if (inputStream is ANTLRFileStream) {
        // Imported grammar tokens belong to a different file.
        return inputStream.sourceName == file.virtualFile.canonicalPath
    }
    return true
}
