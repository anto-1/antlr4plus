package com.antlr4plus.parsing.actionMap

import java.io.File
import java.nio.charset.StandardCharsets
import java.util.UUID

/** Maps grammar action bodies to generated source regions for navigation and edit synchronization. */
data class ActionLocationMap(
    val grammarFile: File,
    val generatedFile: File,
    val entries: List<Entry>,
) {
    /** Finds a containing grammar range; an empty body matches its insertion offset. */
    fun findByGrammarOffset(offset: Int): Entry? = entries.firstOrNull {
        containsOffset(it.grammarBodyRange, offset)
    }

    fun findByGeneratedOffset(offset: Int): Entry? = entries.firstOrNull {
        containsOffset(it.generatedBodyRange, offset)
    }

    private fun containsOffset(range: IntRange, offset: Int): Boolean =
        if (range.isEmpty()) offset == range.first else offset in range

    data class Entry(
        val id: ActionId,
        /** One-based source ordinal across all extracted code blocks, used in generated sentinels. */
        val sourceOrdinal: Int? = null,
        val kind: String,
        val scope: String?,
        /** Inclusive grammar body offsets, excluding braces; may be empty. */
        val grammarBodyRange: IntRange,
        /** Inclusive generated body offsets; may be empty. */
        val generatedBodyRange: IntRange,
        /** Normalized body text for matching. */
        val body: String,
        /** Exact grammar body text, including whitespace. */
        val rawBody: String = body,
    )
}

internal fun containsActionOffset(startOffset: Int, endOffsetExclusive: Int, offset: Int): Boolean =
    if (startOffset == endOffsetExclusive) {
        offset == startOffset
    } else {
        offset in startOffset until endOffsetExclusive
    }

internal fun actionRangeEndExclusive(range: IntRange): Int =
    if (range.isEmpty()) {
        range.first.coerceAtLeast(0)
    } else {
        (range.last + 1).coerceAtLeast(range.first)
    }

/** Converts the end offset without clamping; empty ranges retain their insertion offset. */
internal fun unclampedActionRangeEndExclusive(range: IntRange): Int =
    if (range.isEmpty()) range.first else range.last + 1

/** Identifies an action by grammar path and brace position; moving the brace changes the ID. */
data class ActionId(
    val grammarPath: String,
    val line: Int,
    val column: Int,
) {
    val uuid: String
        get() =
            UUID.nameUUIDFromBytes(
                    "${normalizedGrammarPath()}:$line:$column".toByteArray(StandardCharsets.UTF_8),
                )
                .toString()

    override fun toString(): String = "$grammarPath:$line:$column"

    private fun normalizedGrammarPath(): String =
        try {
            File(grammarPath).canonicalPath
        } catch (_: Exception) {
            grammarPath
        }
}
