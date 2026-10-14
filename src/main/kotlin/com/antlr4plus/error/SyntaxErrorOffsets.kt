package com.antlr4plus.error

import com.antlr4plus.parsing.InputTextCoordinates
import com.intellij.openapi.util.TextRange
import org.antlr.intellij.adaptor.parser.SyntaxError

/** Converts token ranges or ANTLR line/column positions without splitting surrogate pairs. */
internal fun syntaxErrorEditorRange(error: SyntaxError, coordinates: InputTextCoordinates): TextRange? {
    error.offendingSymbol?.let { token ->
        coordinates.inclusiveRange(token.startIndex, token.stopIndex)?.let { (start, stop) ->
            return TextRange(start, stop + 1)
        }
    }
    val start = coordinates.lineColumnOffset(error.line, error.charPositionInLine) ?: return null
    return TextRange(start, coordinates.characterEnd(start))
}
