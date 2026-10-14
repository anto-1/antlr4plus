package com.antlr4plus.parsing

import com.antlr4plus.parsing.ParsingResult.TokenInfo
import com.antlr4plus.parsing.kotlin.KotlinTreeAdapter
import com.antlr4plus.util.rethrowFatalOrCancellation
import org.antlr.v4.runtime.Token
import org.antlr.v4.runtime.tree.ParseTree
import org.antlr.v4.runtime.tree.TerminalNode

internal fun tokenInfoFromTerminal(terminal: ParseTree): TokenInfo? {
    val range = terminalEditorRange(terminal) ?: return null
    return when (terminal) {
        is TerminalNode -> {
            val token = terminal.symbol ?: return null
            TokenInfo(
                type = token.type,
                text = token.text,
                line = token.line,
                charPositionInLine = token.charPositionInLine,
                startIndex = range.first,
                stopIndex = range.second,
                channel = token.channel,
                tokenIndex = token.tokenIndex,
            )
        }
        is KotlinTreeAdapter -> {
            if (!terminal.isTerminalNode()) return null
            val symbol = terminal.getSymbol() ?: return null
            TokenInfo(
                type = terminal.getTokenType(),
                text = terminal.getTokenText(),
                line = readKotlinTokenInt(symbol, "getLine", 0),
                charPositionInLine = readKotlinTokenInt(symbol, "getCharPositionInLine", 0),
                startIndex = range.first,
                stopIndex = range.second,
                channel = readKotlinTokenInt(symbol, "getChannel", Token.DEFAULT_CHANNEL),
                tokenIndex = readKotlinTokenInt(symbol, "getTokenIndex", -1),
            )
        }
        else -> null
    }
}

/** Inclusive UTF-16 range for a token, including both halves of supplementary characters. */
internal fun terminalEditorRange(terminal: ParseTree): Pair<Int, Int>? =
    when (terminal) {
        is TerminalNode -> terminal.symbol?.let(::editorTokenRange)
        is KotlinTreeAdapter ->
            if (terminal.isTerminalNode()) terminal.getSymbol()?.let { kotlinTokenRange(it, it) } else null
        else -> null
    }

private fun kotlinTokenRange(start: Any, stop: Any): Pair<Int, Int>? {
    val from = readKotlinTokenInt(start, "getStartIndex", -1)
    val to = readKotlinTokenInt(stop, "getStopIndex", -1)
    val coordinates = coordinatesForKotlinToken(start)
    return if (coordinates == null) from to to else coordinates.inclusiveRange(from, to)
}

/** Inclusive UTF-16 range; parser and token objects retain their native input indices. */
internal fun ruleCharacterRange(rule: ParseTree): Pair<Int, Int>? =
    when (rule) {
        is org.antlr.v4.runtime.ParserRuleContext -> {
            val start = rule.start
            if (start == null) null
            else {
                val stop = rule.stop ?: start
                val coordinates = coordinatesForInput(start.inputStream)
                if (coordinates == null) start.startIndex to stop.stopIndex
                else coordinates.inclusiveRange(start.startIndex, stop.stopIndex)
            }
        }
        is KotlinTreeAdapter -> {
            val start = rule.getStart()
            if (start == null) null else kotlinTokenRange(start, rule.getStop() ?: start)
        }
        else -> null
    }

internal fun findTerminalNodeAtOffset(tree: ParseTree, offset: Int): ParseTree? {
    if (offset < 0) return null
    val pending = ArrayDeque<ParseTree>()
    pending.addLast(tree)
    while (pending.isNotEmpty()) {
        val current = pending.removeLast()
        if (current is TerminalNode || (current is KotlinTreeAdapter && current.isTerminalNode())) {
            val range = terminalEditorRange(current)
            if (range != null && offset in range.first..range.second) return current
        } else {
            // Recovered trees can have incomplete parent ranges; visit terminals in source order.
            for (i in current.childCount - 1 downTo 0) {
                current.getChild(i)?.let(pending::addLast)
            }
        }
    }
    return null
}

private fun readKotlinTokenInt(token: Any, getter: String, fallback: Int): Int =
    try {
        val method = token.javaClass.getMethod(getter)
        method.invoke(token) as? Int ?: fallback
    } catch (e: Exception) {
        rethrowFatalOrCancellation(e)
        fallback
    }
