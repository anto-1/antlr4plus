package com.antlr4plus.statistics

import com.antlr4plus.parsing.editorTokenRange
import org.antlr.v4.runtime.TokenStream
import org.antlr.v4.runtime.atn.DecisionEventInfo

/** Decision events contain token indices, not input character offsets. */
internal fun decisionEventEditorRange(tokens: TokenStream?, event: DecisionEventInfo): Pair<Int, Int>? {
    tokens ?: return null
    if (event.startIndex < 0 || event.stopIndex < event.startIndex || event.stopIndex >= tokens.size()) return null
    val start = editorTokenRange(tokens.get(event.startIndex))?.first ?: return null
    val stop = editorTokenRange(tokens.get(event.stopIndex))?.second ?: return null
    return (start to stop).takeIf { start >= 0 && stop >= start - 1 }
}
