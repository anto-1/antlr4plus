package com.antlr4plus.toolWindow

import com.antlr4plus.filebinding.ProjectPathPolicy
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.TextAttributes
import java.awt.Color
import java.awt.Font
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Paths

/** Syntax coloring and grammar-path validation for the binding staging editor. */
internal class BindingEditorHighlighter {

    private val commentColor = Color(0x99, 0x99, 0x99)
    private val g4pPathColor = Color(0x2A, 0xAC, 0xB8)
    private val grammarPathColor = Color(0xC7, 0x7D, 0xBB)
    private val arrowColor = Color(0x80, 0x80, 0x80)
    private val invalidColor = Color(0x99, 0x99, 0x99)
    private val errorColor = Color(0xFF, 0x00, 0x00)

    private val commentAttributes =
        TextAttributes().apply {
            foregroundColor = commentColor
            fontType = Font.ITALIC
        }

    private val g4pPathAttributes =
        TextAttributes().apply {
            foregroundColor = g4pPathColor
        }

    private val grammarPathAttributes =
        TextAttributes().apply {
            foregroundColor = grammarPathColor
        }

    private val arrowAttributes =
        TextAttributes().apply {
            foregroundColor = arrowColor
            fontType = Font.BOLD
        }

    private val invalidLineAttributes =
        TextAttributes().apply {
            foregroundColor = invalidColor
        }

    private val invalidPathErrorAttributes =
        TextAttributes().apply {
            effectType = com.intellij.openapi.editor.markup.EffectType.WAVE_UNDERSCORE
            effectColor = errorColor
        }

    /** Applies precomputed syntax and path highlighting on the EDT. */
    fun applyHighlighting(
        editor: EditorEx,
        text: String,
        invalidGrammarPaths: Set<String>?,
    ) {
        val document = editor.document
        val markupModel = editor.markupModel

        markupModel.removeAllHighlighters()

        if (text.isEmpty()) return

        var lineStart = 0
        for (lineNum in 0 until document.lineCount) {
            val lineEnd = document.getLineEndOffset(lineNum)
            val lineText = text.substring(lineStart, lineEnd)
            val trimmedLine = lineText.trim()

            if (trimmedLine.isNotEmpty()) {
                when {
                    trimmedLine.startsWith("#") -> {
                        markupModel.addRangeHighlighter(
                            lineStart,
                            lineEnd,
                            HighlighterLayer.SYNTAX,
                            commentAttributes,
                            HighlighterTargetArea.EXACT_RANGE,
                        )
                    }
                    trimmedLine.contains("->") -> {
                        highlightBindingLine(markupModel, lineText, lineStart, invalidGrammarPaths)
                    }
                    else -> {
                        markupModel.addRangeHighlighter(
                            lineStart,
                            lineEnd,
                            HighlighterLayer.SYNTAX,
                            invalidLineAttributes,
                            HighlighterTargetArea.EXACT_RANGE,
                        )
                    }
                }
            }

            lineStart = lineEnd + 1
        }
    }

    private fun highlightBindingLine(
        markupModel: com.intellij.openapi.editor.markup.MarkupModel,
        lineText: String,
        lineStart: Int,
        invalidGrammarPaths: Set<String>?,
    ) {
        val arrowIndex = lineText.indexOf("->")
        if (arrowIndex == -1) return

        val g4pPath = lineText.substring(0, arrowIndex).trim()
        val grammarPath = lineText.substring(arrowIndex + 2).trim()

        val g4pStartInLine = lineText.indexOf(g4pPath)
        val arrowStartInLine = lineText.indexOf("->")
        val grammarStartInLine = arrowIndex + 2 + lineText.substring(arrowIndex + 2).indexOfFirst { !it.isWhitespace() }

        if (g4pPath.isNotEmpty() && g4pStartInLine >= 0) {
            val g4pStart = lineStart + g4pStartInLine
            val g4pEnd = g4pStart + g4pPath.length
            markupModel.addRangeHighlighter(
                g4pStart,
                g4pEnd,
                HighlighterLayer.SYNTAX,
                g4pPathAttributes,
                HighlighterTargetArea.EXACT_RANGE,
            )

            if (invalidGrammarPaths != null && !g4pPath.endsWith(".g4p")) {
                markupModel.addRangeHighlighter(
                    g4pStart,
                    g4pEnd,
                    HighlighterLayer.ERROR,
                    invalidPathErrorAttributes,
                    HighlighterTargetArea.EXACT_RANGE,
                )
            }
        }

        if (arrowStartInLine >= 0) {
            val arrowStart = lineStart + arrowStartInLine
            val arrowEnd = arrowStart + 2
            markupModel.addRangeHighlighter(
                arrowStart,
                arrowEnd,
                HighlighterLayer.SYNTAX + 1,
                arrowAttributes,
                HighlighterTargetArea.EXACT_RANGE,
            )
        }

        if (grammarPath.isNotEmpty() && grammarStartInLine >= 0) {
            val grammarStart = lineStart + grammarStartInLine
            val grammarEnd = grammarStart + grammarPath.length
            markupModel.addRangeHighlighter(
                grammarStart,
                grammarEnd,
                HighlighterLayer.SYNTAX,
                grammarPathAttributes,
                HighlighterTargetArea.EXACT_RANGE,
            )

            if (invalidGrammarPaths?.contains(grammarPath) == true) {
                markupModel.addRangeHighlighter(
                    grammarStart,
                    grammarEnd,
                    HighlighterLayer.ERROR,
                    invalidPathErrorAttributes,
                    HighlighterTargetArea.EXACT_RANGE,
                )
            }
        }
    }

    /** Checks unique grammar paths using filesystem I/O. Call off the EDT. */
    fun findInvalidGrammarPaths(
        projectRootPath: String,
        text: String,
        isCurrent: () -> Boolean,
    ): Set<String> {
        val projectRoot =
            try {
                Paths.get(projectRootPath)
            } catch (_: InvalidPathException) {
                return emptySet()
            }
        val invalidPaths = mutableSetOf<String>()
        val checkedPaths = mutableSetOf<String>()

        for (line in text.lineSequence()) {
            if (!isCurrent()) return emptySet()
            val trimmedLine = line.trim()
            if (trimmedLine.isEmpty() || trimmedLine.startsWith("#")) continue
            val arrowIndex = line.indexOf("->")
            if (arrowIndex < 0) continue
            val grammarPath = line.substring(arrowIndex + 2).trim()
            if (grammarPath.isEmpty() || !checkedPaths.add(grammarPath)) continue

            val exists = ProjectPathPolicy.resolve(projectRoot, grammarPath)?.let(Files::exists) == true
            if (!exists) invalidPaths.add(grammarPath)
        }
        return invalidPaths
    }
}
