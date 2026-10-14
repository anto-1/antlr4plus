package com.antlr4plus.editor

import com.antlr4plus.parsing.ParsingResult
import com.antlr4plus.util.escapeHtml
import org.antlr.v4.runtime.Token
import org.antlr.v4.runtime.atn.AmbiguityInfo
import org.antlr.v4.runtime.atn.LookaheadEventInfo

internal fun buildParseRegionHintText(
    parsingResult: ParsingResult,
    rulePath: List<String>,
    tokenType: Int,
): String = buildString {
    append("<html>")

    rulePath.forEachIndexed { index, ruleName ->
        if (index > 0) {
            append("<br>")
        }
        repeat(index) { append("&nbsp;&nbsp;") }
        append("<b>${escapeHtml(ruleName)}</b>")
    }

    val tokenName =
        if (tokenType == Token.EOF) {
            "EOF"
        } else {
            parsingResult.getSymbolicName(tokenType) ?: parsingResult.getDisplayName(tokenType) ?: "token"
        }
    if (rulePath.isNotEmpty()) {
        append("<br>")
        repeat(rulePath.size) { append("&nbsp;&nbsp;") }
    }
    append("<i>→ ${escapeHtml(tokenName)}</i>")

    append("</html>")
}

internal fun buildTokenInfoText(
    parsingResult: ParsingResult,
    tokenInfo: ParsingResult.TokenInfo,
): String = buildString {
    append("<html>")

    val tokenTypeName =
        if (tokenInfo.type == Token.EOF) {
            "EOF"
        } else if (tokenInfo.type >= 0) {
            parsingResult.getSymbolicName(tokenInfo.type)
                ?: parsingResult.getDisplayName(tokenInfo.type)
                ?: "type=${tokenInfo.type}"
        } else {
            "INVALID"
        }

    append("<b>${escapeHtml(tokenTypeName)}</b>")

    val tokenText =
        tokenInfo.text.let { text ->
            val visible = text.replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
            val truncated =
                if (visible.length > 30) {
                    visible.substring(0, 30) + "..."
                } else {
                    visible
                }
            escapeHtml(truncated)
        }

    if (tokenText.isNotEmpty()) {
        append(" : '$tokenText'")
    }

    append("<br>")
    append("line ${tokenInfo.line}:${tokenInfo.charPositionInLine}")
    append(", index ${tokenInfo.startIndex}..${tokenInfo.stopIndex}")

    if (tokenInfo.channel != Token.DEFAULT_CHANNEL) {
        append("<br>")
        append("channel: ${tokenInfo.channel}")
        if (tokenInfo.channel == Token.HIDDEN_CHANNEL) {
            append(" (HIDDEN)")
        }
    }

    append("<br>")
    append("token index: ${tokenInfo.tokenIndex}")

    append("</html>")
}

internal fun buildDecisionEventHint(
    ambigInfo: AmbiguityInfo?,
    lookaheadInfo: LookaheadEventInfo?,
): StringBuilder? {
    val tooltipParts = mutableListOf<String>()

    if (lookaheadInfo != null) {
        val k = lookaheadInfo.stopIndex - lookaheadInfo.startIndex + 1
        tooltipParts.add("Deepest lookahead k=$k")
    }

    if (ambigInfo != null) {
        tooltipParts.add("Ambiguous upon alts ${ambigInfo.ambigAlts}")
    }

    if (tooltipParts.isEmpty()) return null

    return StringBuilder("<html>").apply {
        tooltipParts.forEachIndexed { index, part ->
            if (index > 0) append("<br>")
            append("<b>$part</b>")
        }
        append("</html>")
    }
}
