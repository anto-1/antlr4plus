package com.antlr4plus.parsing.actionMap

/** Stamps action boundaries in generated preview source for navigation, highlighting and edit sync. */
object GeneratedActionSentinelSupport {

    private val LEGACY_MARKER_REGEX =
        Regex(
            "ANTLR4PLUS:(BEGIN|END):([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}|\\d+:\\d+)",
        )
    private val SIMPLE_MARKER_REGEX = Regex("\\d+")
    private val MARKER_REGEX =
        Regex(
            "/\\*(ANTLR4PLUS:(?:BEGIN|END):(?:[0-9a-fA-F-]{36}|\\d+:\\d+)|\\d+)\\*/",
        )

    data class StampedRegion(
        val id: String,
        val beginCommentRange: IntRange,
        val bodyRange: IntRange,
        val endCommentRange: IntRange,
    )

    data class ParsedStampedRegions(
        val bySourceOrdinal: Map<Int, StampedRegion>,
        val byLegacyToken: Map<String, StampedRegion>,
    ) {
        fun findRegion(sourceOrdinal: Int?, actionId: ActionId): StampedRegion? {
            sourceOrdinal?.let { ordinal ->
                bySourceOrdinal[ordinal]?.let {
                    return it
                }
            }
            byLegacyToken[actionId.uuid.lowercase()]?.let {
                return it
            }
            return byLegacyToken["${actionId.line}:${actionId.column}"]
        }
    }

    fun parseStampedRegions(generatedText: String): ParsedStampedRegions {
        val pendingLegacyBegins = mutableMapOf<String, MatchResult>()
        val pendingSimpleBegins = mutableMapOf<Int, MatchResult>()
        val legacyRegions = linkedMapOf<String, StampedRegion>()
        val simpleRegions = linkedMapOf<Int, StampedRegion>()

        for (match in MARKER_REGEX.findAll(generatedText)) {
            val markerBody = match.groupValues[1]
            val legacyMatch = LEGACY_MARKER_REGEX.matchEntire(markerBody)
            if (legacyMatch != null) {
                val kind = legacyMatch.groupValues[1]
                val uuid = legacyMatch.groupValues[2].lowercase()
                when (kind) {
                    "BEGIN" -> pendingLegacyBegins[uuid] = match
                    "END" -> {
                        val beginMatch = pendingLegacyBegins.remove(uuid) ?: continue
                        legacyRegions[uuid] = buildStampedRegion(uuid, beginMatch, match)
                    }
                }
                continue
            }

            if (!SIMPLE_MARKER_REGEX.matches(markerBody)) continue
            val ordinal = markerBody.toIntOrNull() ?: continue
            val beginMatch = pendingSimpleBegins.remove(ordinal)
            if (beginMatch == null) {
                pendingSimpleBegins[ordinal] = match
            } else {
                simpleRegions[ordinal] = buildStampedRegion(markerBody, beginMatch, match)
            }
        }

        return ParsedStampedRegions(
            bySourceOrdinal = simpleRegions,
            byLegacyToken = legacyRegions,
        )
    }

    internal fun renderStampedText(
        generatedText: String,
        entries: List<ActionLocationMap.Entry>,
    ): String {
        if (entries.isEmpty()) return generatedText

        val builder = StringBuilder(sanitizeGeneratedSemanticPredicates(generatedText, entries))
        val sentinelIdsByActionId =
            entries.withIndex().associate { (index, entry) ->
                entry.id to (entry.sourceOrdinal ?: (index + 1))
            }
        val sortedEntries =
            entries.sortedWith(
                compareByDescending<ActionLocationMap.Entry> { it.generatedBodyRange.first }
                    .thenByDescending { unclampedActionRangeEndExclusive(it.generatedBodyRange) },
            )

        for (entry in sortedEntries) {
            val startOffset = entry.generatedBodyRange.first.coerceIn(0, builder.length)
            val endOffsetExclusive =
                unclampedActionRangeEndExclusive(entry.generatedBodyRange).coerceIn(startOffset, builder.length)
            val normalizedBody =
                normalizeGeneratedPreviewBody(
                    entry,
                    builder.substring(startOffset, endOffsetExclusive),
                )
            if (normalizedBody != builder.substring(startOffset, endOffsetExclusive)) {
                builder.replace(startOffset, endOffsetExclusive, normalizedBody)
            }
            val normalizedEndOffsetExclusive = startOffset + normalizedBody.length
            val sentinelId = sentinelIdsByActionId[entry.id] ?: continue
            builder.insert(normalizedEndOffsetExclusive, buildSentinel(sentinelId))
            builder.insert(startOffset, buildSentinel(sentinelId))
        }

        return builder.toString()
    }

    private fun buildStampedRegion(
        id: String,
        beginMatch: MatchResult,
        endMatch: MatchResult,
    ): StampedRegion {
        val beginEndExclusive = beginMatch.range.last + 1
        val endStart = endMatch.range.first
        val endEndExclusive = endMatch.range.last + 1
        return StampedRegion(
            id = id,
            beginCommentRange = beginMatch.range.first until beginEndExclusive,
            bodyRange = beginEndExclusive until endStart,
            endCommentRange = endMatch.range.first until endEndExclusive,
        )
    }

    private fun buildSentinel(id: Int): String = "/*$id*/"

    private fun normalizeGeneratedPreviewBody(
        entry: ActionLocationMap.Entry,
        text: String,
    ): String =
        when (entry.kind) {
            "Semantic Predicate" -> stripTrailingSemicolons(text)
            else -> text
        }

    private fun stripTrailingSemicolons(text: String): String {
        var suffixStart = text.length
        while (suffixStart > 0 && text[suffixStart - 1].isWhitespace()) {
            suffixStart--
        }

        var bodyEnd = suffixStart
        while (bodyEnd > 0 && text[bodyEnd - 1] == ';') {
            bodyEnd--
        }

        if (bodyEnd == suffixStart) return text

        return text.substring(0, bodyEnd) + text.substring(suffixStart)
    }

    private fun sanitizeGeneratedSemanticPredicates(
        generatedText: String,
        entries: List<ActionLocationMap.Entry>,
    ): String {
        var result = generatedText

        entries
            .filter { it.kind == "Semantic Predicate" }
            .forEach { entry ->
                val rawBody = entry.body
                val normalizedBody = stripTrailingSemicolons(rawBody)
                if (normalizedBody == rawBody) return@forEach

                val escapedBody = Regex.escape(rawBody)
                val predicatePattern = Regex("""if\s*\(!\((\s*)$escapedBody(\s*)\)\)""")
                val returnPattern = Regex("""return(\s+)$escapedBody(\s*);""")

                result =
                    predicatePattern.replace(result) { match ->
                        "if (!(${match.groupValues[1]}$normalizedBody${match.groupValues[2]}))"
                    }
                result =
                    returnPattern.replace(result) { match ->
                        "return${match.groupValues[1]}$normalizedBody${match.groupValues[2]};"
                    }
                result = result.replace("\"$rawBody\"", "\"$normalizedBody\"")
            }

        return result
    }
}
