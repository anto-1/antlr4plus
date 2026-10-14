package com.antlr4plus.timeline

import com.antlr4plus.parsing.editorTokenRange
import org.antlr.v4.runtime.Token

/** A token or captured lexer segment with the mode and stack depth from before matching. */
data class TokenWithMode(
    val token: Token,
    val mode: Int,
    val modeName: String,
    val modeDepth: Int,
) {
    val text: String
        get() = token.text ?: ""

    val type: Int
        get() = token.type

    private val editorRange = editorTokenRange(token)

    /** UTF-16 offsets used by editor selection; the underlying token keeps native indices. */
    val startIndex: Int
        get() = editorRange?.first ?: -1

    /** Inclusive UTF-16 stop offset. */
    val stopIndex: Int
        get() = editorRange?.second ?: -1

    /** One-based input line. */
    val line: Int
        get() = token.line

    /** Zero-based position within the input line. */
    val charPositionInLine: Int
        get() = token.charPositionInLine

    val channel: Int
        get() = token.channel

    /** Tests the HIDDEN channel; skipped segments can still have the default channel. */
    val isHidden: Boolean
        get() = token.channel == Token.HIDDEN_CHANNEL
}

data class TokenTimeline(
    val tokens: List<TokenWithMode>,
    val modeNames: List<String>,
    val tokenTypeNames: List<String>,
) {
    /** Splits tokens into contiguous mode runs in input order. */
    fun getModeSections(): List<ModeSection> {
        if (tokens.isEmpty()) return emptyList()

        val sections = mutableListOf<ModeSection>()
        var currentMode = tokens.first().mode
        var currentModeName = tokens.first().modeName
        var currentTokens = mutableListOf<TokenWithMode>()
        var startIndex = 0

        for ((index, token) in tokens.withIndex()) {
            if (token.mode != currentMode) {
                sections.add(
                    ModeSection(
                        mode = currentMode,
                        modeName = currentModeName,
                        tokens = currentTokens.toList(),
                        startTokenIndex = startIndex,
                        endTokenIndex = index - 1,
                    ),
                )
                currentMode = token.mode
                currentModeName = token.modeName
                currentTokens = mutableListOf()
                startIndex = index
            }
            currentTokens.add(token)
        }

        if (currentTokens.isNotEmpty()) {
            sections.add(
                ModeSection(
                    mode = currentMode,
                    modeName = currentModeName,
                    tokens = currentTokens.toList(),
                    startTokenIndex = startIndex,
                    endTokenIndex = tokens.size - 1,
                ),
            )
        }

        return sections
    }

    /** Returns mode names in order of first appearance. */
    fun getUsedModes(): List<String> = tokens.map { it.modeName }.distinct()

    /** Returns channel number/name pairs sorted by channel number. */
    fun getUsedChannels(): List<Pair<Int, String>> =
        tokens
            .map { it.channel }
            .distinct()
            .sorted()
            .map { channel ->
                val name =
                    when (channel) {
                        Token.DEFAULT_CHANNEL -> "DEFAULT"
                        Token.HIDDEN_CHANNEL -> "HIDDEN"
                        else -> "Channel $channel"
                    }
                channel to name
            }

    fun getTokenTypeName(token: TokenWithMode): String =
        if (token.type >= 0 && token.type < tokenTypeNames.size) {
            tokenTypeNames[token.type]
        } else if (token.type == Token.EOF) {
            "EOF"
        } else {
            "<UNKNOWN:${token.type}>"
        }
}

data class ModeSection(
    val mode: Int,
    val modeName: String,
    val tokens: List<TokenWithMode>,
    val startTokenIndex: Int,
    val endTokenIndex: Int,
)
