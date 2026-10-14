package com.antlr4plus.timeline

import org.antlr.v4.runtime.Token

internal fun StringBuilder.appendTokenTimelineMarkdown(
    currentTimeline: TokenTimeline,
    tokensToExport: List<Pair<Int, TokenWithMode>>,
    isPartialExport: Boolean,
) {
    appendLine("## Token Timeline")
    appendLine()

    if (isPartialExport) {
        appendLine("**Selected tokens:** ${tokensToExport.size} of ${currentTimeline.tokens.size}")
    } else {
        appendLine("**Total tokens:** ${currentTimeline.tokens.size}")
    }
    val usedModes = currentTimeline.getUsedModes()
    if (usedModes.isNotEmpty()) {
        appendLine("**Modes used:** ${usedModes.joinToString(", ")}")
    }
    appendLine()

    appendLine("| # | Type | Text | Mode | Line:Col | Channel |")
    appendLine("|---|------|------|------|----------|---------|")

    tokensToExport.forEach { (index, token) ->
        val typeName = currentTimeline.getTokenTypeName(token)
        val displayText =
            token.text.replace("|", "\\|").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t").take(50).let {
                if (token.text.length > 50) "$it..." else it
            }
        val channelName =
            when (token.channel) {
                Token.DEFAULT_CHANNEL -> "DEFAULT"
                Token.HIDDEN_CHANNEL -> "HIDDEN"
                else -> "Ch${token.channel}"
            }
        appendLine(
            "| $index | `$typeName` | `$displayText` | ${token.modeName} | ${token.line}:${token.charPositionInLine} | $channelName |",
        )
    }
}
