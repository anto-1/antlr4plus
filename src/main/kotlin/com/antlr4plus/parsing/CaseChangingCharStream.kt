package com.antlr4plus.parsing

import org.antlr.v4.runtime.CharStream
import org.antlr.v4.runtime.misc.Interval

/** Folds lookahead case while preserving the original text returned by [getText]. */
class CaseChangingCharStream(val stream: CharStream, val upper: Boolean) : CharStream by stream {
    // Keep the nullable accessor signatures for Kotlin callers.
    override fun getText(interval: Interval?): String? = stream.getText(interval)

    override fun LA(i: Int): Int {
        val c = stream.LA(i)
        return when {
            c <= 0 -> c // Preserve EOF and invalid character codes.
            upper -> Character.toUpperCase(c)
            else -> Character.toLowerCase(c)
        }
    }

    override fun getSourceName(): String? = stream.sourceName
}
