package com.antlr4plus.parsing.actionMap

import com.antlr4plus.parsing.actionMap.GeneratedActionScopes.ScopeKey
import com.antlr4plus.parsing.actionMap.GeneratedActionScopes.TextWindow
import com.antlr4plus.parsing.actionMap.GeneratedActionScopes.findSearchWindows
import com.antlr4plus.parsing.embedded.EmbeddedCode
import com.antlr4plus.parsing.embedded.GrammarCodeExtractor
import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.nio.charset.Charset

/** Matches grammar actions to generated sentinels or body text, omitting unmatched actions. */
object ActionLocationMapBuilder {

    private val LOG = Logger.getInstance(ActionLocationMapBuilder::class.java)

    private data class GeneratedMatch(
        val range: IntRange,
        val nextLineCursor: Int,
    )

    private class LineSplitResult(
        val lines: List<String>,
        val lineStarts: IntArray,
    )

    private data class NonBlankBodyLine(
        val text: String,
        val leadingWhitespaceCount: Int,
        val trailingWhitespaceCount: Int,
    )

    /** Code kinds supported by the generated-source matcher. */
    private val MAPPABLE_KINDS =
        setOf(
            EmbeddedCode.CodeType.MEMBERS,
            EmbeddedCode.CodeType.SCOPED_MEMBERS,
            EmbeddedCode.CodeType.PARSER_ACTION,
            EmbeddedCode.CodeType.LEXER_ACTION,
            EmbeddedCode.CodeType.SEMANTIC_PREDICATE,
            EmbeddedCode.CodeType.ARGUMENT_BLOCK,
            EmbeddedCode.CodeType.RULE_INIT,
            EmbeddedCode.CodeType.RULE_AFTER,
            EmbeddedCode.CodeType.EXCEPTION_HANDLER,
            EmbeddedCode.CodeType.FINALLY_CLAUSE,
        )

    fun build(
        grammarFile: File,
        generatedFile: File,
        grammarCharset: Charset = Charsets.UTF_8,
    ): ActionLocationMap? {
        if (!grammarFile.exists()) {
            LOG.warn(
                "ActionLocationMapBuilder: grammar file does not exist: ${grammarFile.absolutePath}",
            )
            return null
        }
        val grammarText =
            try {
                grammarFile.readText(grammarCharset)
            } catch (e: Exception) {
                LOG.warn("ActionLocationMapBuilder: failed to read grammar: ${e.message}")
                return null
            }

        return buildFromText(grammarFile.path, grammarText, generatedFile)
    }

    fun buildFromText(
        grammarPath: String,
        grammarText: String,
        generatedFile: File,
    ): ActionLocationMap? {
        if (!generatedFile.exists()) {
            LOG.warn(
                "ActionLocationMapBuilder: generated file does not exist: ${generatedFile.absolutePath}",
            )
            return null
        }

        val generatedText =
            try {
                generatedFile.readText()
            } catch (e: Exception) {
                LOG.warn("ActionLocationMapBuilder: failed to read generated source: ${e.message}")
                return null
            }

        return buildFromText(grammarPath, grammarText, generatedFile.path, generatedText)
    }

    fun buildFromText(
        grammarPath: String,
        grammarText: String,
        generatedPath: String,
        generatedText: String,
    ): ActionLocationMap? {
        val embeddedCode =
            try {
                GrammarCodeExtractor.extract(
                    grammarText,
                    grammarPath,
                    includeBlankCodeBlocks = true,
                )
            } catch (e: Exception) {
                LOG.warn(
                    "ActionLocationMapBuilder: GrammarCodeExtractor.extract(text) failed: ${e.message}",
                )
                return null
            }

        return buildFromCode(
            File(grammarPath),
            File(generatedPath),
            grammarText,
            generatedText,
            embeddedCode,
        )
    }

    private fun buildFromCode(
        grammarFile: File,
        generatedFile: File,
        grammarText: String,
        generatedText: String,
        embeddedCode: List<EmbeddedCode>,
    ): ActionLocationMap {
        if (grammarText.isEmpty() && generatedText.isEmpty()) {
            return ActionLocationMap(grammarFile, generatedFile, emptyList())
        }

        val sortedActions =
            embeddedCode
                .filter { it.type in MAPPABLE_KINDS }
                .sortedWith(compareBy({ it.startLine }, { it.startColumn }))
        val sourceOrdinalsByActionId =
            embeddedCode
                .sortedWith(
                    compareBy(
                        { it.startLine },
                        { it.startColumn },
                        { it.endLine },
                        { it.endColumn },
                    ),
                )
                .mapIndexed { index, action ->
                    ActionId(
                        grammarPath = grammarFile.absolutePath,
                        line = action.startLine,
                        column = action.startColumn,
                    ) to (index + 1)
                }
                .toMap()

        if (sortedActions.isEmpty()) {
            return ActionLocationMap(grammarFile, generatedFile, emptyList())
        }

        val entries = mutableListOf<ActionLocationMap.Entry>()

        val lineSplit = splitLinesWithOffsets(generatedText)
        val generatedLines = lineSplit.lines
        val lineStarts = lineSplit.lineStarts
        var generatedLineCursor = 0
        val scopeWindowCache = mutableMapOf<ScopeKey, List<TextWindow>>()
        val stampedGeneratedRegions = GeneratedActionSentinelSupport.parseStampedRegions(generatedText)

        for (action in sortedActions) {
            val actionId =
                ActionId(
                    grammarPath = grammarFile.absolutePath,
                    line = action.startLine,
                    column = action.startColumn,
                )
            val sourceOrdinal = sourceOrdinalsByActionId[actionId]
            // Use extractor offsets and verify the current body text before mapping.
            val grammarBodyRange = computeGrammarBodyRangeFromOffsets(grammarText, action) ?: continue
            val fullBody = action.content
            val generatedMatch =
                findStampedGeneratedRange(
                    actionId = actionId,
                    sourceOrdinal = sourceOrdinal,
                    stampedGeneratedRegions = stampedGeneratedRegions,
                    lineStarts = lineStarts,
                )
                    ?: if (fullBody.isBlank()) {
                        findBlankGeneratedRange(
                            action = action,
                            generatedFile = generatedFile,
                            generatedText = generatedText,
                            generatedLines = generatedLines,
                            lineStarts = lineStarts,
                            generatedLineCursor = generatedLineCursor,
                            scopeWindowCache = scopeWindowCache,
                        )
                    } else {
                        findNonBlankGeneratedRange(
                            action = action,
                            generatedFile = generatedFile,
                            fullBody = fullBody,
                            generatedText = generatedText,
                            generatedLines = generatedLines,
                            lineStarts = lineStarts,
                            generatedLineCursor = generatedLineCursor,
                            scopeWindowCache = scopeWindowCache,
                        )
                    }
                    ?: continue

            generatedLineCursor = generatedMatch.nextLineCursor

            entries.add(
                ActionLocationMap.Entry(
                    id = actionId,
                    sourceOrdinal = sourceOrdinal,
                    kind = action.type.displayName,
                    scope = action.scopeName,
                    grammarBodyRange = grammarBodyRange,
                    generatedBodyRange = generatedMatch.range,
                    body = normalizeBody(fullBody),
                    rawBody = fullBody,
                ),
            )
        }

        LOG.info(
            "ActionLocationMapBuilder: built ${entries.size}/${sortedActions.size} mappings for ${grammarFile.name} -> ${generatedFile.name}",
        )
        return ActionLocationMap(grammarFile, generatedFile, entries)
    }

    private fun findStampedGeneratedRange(
        actionId: ActionId,
        sourceOrdinal: Int?,
        stampedGeneratedRegions: GeneratedActionSentinelSupport.ParsedStampedRegions,
        lineStarts: IntArray,
    ): GeneratedMatch? {
        val stampedRegion =
            stampedGeneratedRegions.findRegion(
                sourceOrdinal = sourceOrdinal,
                actionId = actionId,
            ) ?: return null
        val nextCursorOffset =
            if (stampedRegion.endCommentRange.isEmpty()) {
                stampedRegion.bodyRange.first
            } else {
                stampedRegion.endCommentRange.last
            }
        return GeneratedMatch(
            range = stampedRegion.bodyRange,
            nextLineCursor = lineIndexAtOffset(lineStarts, nextCursorOffset) + 1,
        )
    }

    private fun findNonBlankGeneratedRange(
        action: EmbeddedCode,
        generatedFile: File,
        fullBody: String,
        generatedText: String,
        generatedLines: List<String>,
        lineStarts: IntArray,
        generatedLineCursor: Int,
        scopeWindowCache: MutableMap<ScopeKey, List<TextWindow>>,
    ): GeneratedMatch? {
        // Match nonblank body lines within generated lines to tolerate indentation and target
        // wrappers.
        val bodyLines = extractNonBlankBodyLines(fullBody)
        if (bodyLines.isEmpty()) return null

        val searchWindows = findSearchWindows(action, generatedFile, generatedText, scopeWindowCache)

        for ((startOffset, endOffsetExclusive) in searchWindows) {
            val windowStartLine = lineIndexAtOffset(lineStarts, startOffset)
            val windowEndLine =
                lineIndexAtOffset(
                    lineStarts,
                    (endOffsetExclusive - 1).coerceAtLeast(startOffset),
                )
            if (windowEndLine < windowStartLine) continue

            val startLine = maxOf(generatedLineCursor, windowStartLine)
            if (startLine > windowEndLine) continue

            for (lineIndex in startLine..windowEndLine) {
                val firstMatch = findBodySubstringInLine(generatedLines[lineIndex], bodyLines.first().text) ?: continue

                var generatedIdx = lineIndex
                var lastMatchLine = lineIndex
                var lastMatch = firstMatch
                var allMatch = true

                for (i in 1 until bodyLines.size) {
                    generatedIdx++
                    while (generatedIdx <= windowEndLine && generatedLines[generatedIdx].trim().isEmpty()) {
                        generatedIdx++
                    }

                    if (generatedIdx > windowEndLine) {
                        allMatch = false
                        break
                    }

                    val lineMatch = findBodySubstringInLine(generatedLines[generatedIdx], bodyLines[i].text)
                    if (lineMatch == null) {
                        allMatch = false
                        break
                    }

                    lastMatchLine = generatedIdx
                    lastMatch = lineMatch
                }

                if (!allMatch) continue

                val preserveInlineBoundaryWhitespace = !fullBody.contains('\n') && !fullBody.contains('\r')
                val leadingBoundaryWhitespace =
                    if (preserveInlineBoundaryWhitespace) {
                        bodyLines.first().leadingWhitespaceCount
                    } else {
                        0
                    }
                val trailingBoundaryWhitespace =
                    if (preserveInlineBoundaryWhitespace) {
                        bodyLines.last().trailingWhitespaceCount
                    } else {
                        0
                    }
                val firstLine = generatedLines[lineIndex]
                var firstLineStartColumn = firstMatch.first.coerceIn(0, firstLine.length)
                repeat(leadingBoundaryWhitespace) {
                    if (firstLineStartColumn > 0 && firstLine[firstLineStartColumn - 1].isWhitespace()) {
                        firstLineStartColumn--
                    }
                }
                val lastLine = generatedLines[lastMatchLine]
                var lastLineEndColumn = (lastMatch.last + 1).coerceIn(0, lastLine.length)
                repeat(trailingBoundaryWhitespace) {
                    if (lastLineEndColumn < lastLine.length && lastLine[lastLineEndColumn].isWhitespace()) {
                        lastLineEndColumn++
                    }
                }
                val generatedBodyStart = lineStarts[lineIndex] + firstLineStartColumn
                val generatedBodyEndExclusive = lineStarts[lastMatchLine] + lastLineEndColumn
                return GeneratedMatch(
                    generatedBodyStart until generatedBodyEndExclusive,
                    lastMatchLine + 1,
                )
            }
        }

        LOG.debug(
            "ActionLocationMapBuilder: body lines not found in generated source for action at " +
                "${action.startLine}:${action.startColumn} (${action.type.displayName})",
        )
        return null
    }

    private fun findBlankGeneratedRange(
        action: EmbeddedCode,
        generatedFile: File,
        generatedText: String,
        generatedLines: List<String>,
        lineStarts: IntArray,
        generatedLineCursor: Int,
        scopeWindowCache: MutableMap<ScopeKey, List<TextWindow>>,
    ): GeneratedMatch? {
        val searchWindows = findSearchWindows(action, generatedFile, generatedText, scopeWindowCache)

        val reverseSearch = action.type == EmbeddedCode.CodeType.RULE_AFTER
        for (window in searchWindows) {
            val windowStartLine = lineIndexAtOffset(lineStarts, window.startOffset)
            val windowEndLine =
                lineIndexAtOffset(
                    lineStarts,
                    (window.endOffsetExclusive - 1).coerceAtLeast(window.startOffset),
                )
            if (windowEndLine < windowStartLine) continue

            val startLine = maxOf(generatedLineCursor, windowStartLine)
            if (startLine > windowEndLine) continue

            val lineIndices = if (reverseSearch) windowEndLine downTo startLine else startLine..windowEndLine
            for (lineIndex in lineIndices) {
                val lineStart = lineStarts[lineIndex]
                if (lineStart < window.startOffset || lineStart >= window.endOffsetExclusive) continue
                if (generatedLines[lineIndex].isNotBlank()) continue

                val insertionOffset = lineStart + generatedLines[lineIndex].length
                return GeneratedMatch(insertionOffset until insertionOffset, lineIndex + 1)
            }

            fallbackBlankInsertionOffset(action, window)?.let { fallbackOffset ->
                return GeneratedMatch(
                    fallbackOffset until fallbackOffset,
                    lineIndexAtOffset(lineStarts, fallbackOffset) + 1,
                )
            }
        }

        LOG.debug(
            "ActionLocationMapBuilder: blank body slot not found in generated source for action at " +
                "${action.startLine}:${action.startColumn} (${action.type.displayName})",
        )
        return null
    }

    private fun findBodySubstringInLine(generatedLine: String, bodyLine: String): IntRange? {
        if (bodyLine.isEmpty()) return null

        val start = generatedLine.indexOf(bodyLine)
        if (start < 0) return null

        return start until (start + bodyLine.length)
    }

    private fun fallbackBlankInsertionOffset(action: EmbeddedCode, window: TextWindow): Int? =
        when (action.type) {
            EmbeddedCode.CodeType.EXCEPTION_HANDLER,
            EmbeddedCode.CodeType.FINALLY_CLAUSE,
            EmbeddedCode.CodeType.RULE_AFTER -> window.startOffset
            else -> null
        }

    private fun lineIndexAtOffset(lineStarts: IntArray, offset: Int): Int {
        if (lineStarts.isEmpty()) return 0

        val clampedOffset = offset.coerceAtLeast(0)
        var low = 0
        var high = lineStarts.lastIndex
        var result = 0

        while (low <= high) {
            val mid = (low + high) ushr 1
            if (lineStarts[mid] <= clampedOffset) {
                result = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }

        return result
    }

    private fun normalizeBody(fullBody: String): String =
        extractNonBlankBodyLines(fullBody).joinToString("\n") { it.text }

    private fun extractNonBlankBodyLines(fullBody: String): List<NonBlankBodyLine> =
        fullBody.lines().mapNotNull { rawLine ->
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty()) {
                null
            } else {
                val leadingWhitespaceCount =
                    rawLine.indexOfFirst { !it.isWhitespace() }.takeIf { it >= 0 } ?: rawLine.length
                val trailingWhitespaceCount = rawLine.length - rawLine.indexOfLast { !it.isWhitespace() } - 1
                NonBlankBodyLine(
                    text = trimmed,
                    leadingWhitespaceCount = leadingWhitespaceCount,
                    trailingWhitespaceCount = trailingWhitespaceCount.coerceAtLeast(0),
                )
            }
        }

    private fun splitLinesWithOffsets(text: String): LineSplitResult {
        val lines = mutableListOf<String>()
        val starts = mutableListOf<Int>()
        var lineStart = 0
        var index = 0

        while (index < text.length) {
            when (text[index]) {
                '\n' -> {
                    starts.add(lineStart)
                    lines.add(text.substring(lineStart, index))
                    index++
                    lineStart = index
                }

                '\r' -> {
                    starts.add(lineStart)
                    lines.add(text.substring(lineStart, index))
                    index++
                    if (index < text.length && text[index] == '\n') {
                        index++
                    }
                    lineStart = index
                }

                else -> index++
            }
        }

        starts.add(lineStart)
        lines.add(text.substring(lineStart))
        return LineSplitResult(lines = lines, lineStarts = starts.toIntArray())
    }

    /** Converts validated extractor offsets into an inclusive body range, excluding delimiters. */
    private fun computeGrammarBodyRangeFromOffsets(
        grammarText: String,
        action: EmbeddedCode,
    ): IntRange? {
        val bodyStart = action.bodyStartOffset
        val bodyEndExclusive = action.bodyEndOffsetExclusive
        if (bodyStart < 0 || bodyEndExclusive < 0) return null
        if (bodyStart > bodyEndExclusive) return null
        if (bodyEndExclusive > grammarText.length) return null
        if (bodyStart == bodyEndExclusive) return bodyStart until bodyEndExclusive

        // The grammar may have changed since extraction; reject stale offsets.
        val computed = grammarText.substring(bodyStart, bodyEndExclusive)
        if (computed != action.content) return null

        return bodyStart until bodyEndExclusive
    }
}
